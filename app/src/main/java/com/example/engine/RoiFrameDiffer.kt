package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.RoiRect
import kotlin.math.abs

/**
 * Lightweight pixel fingerprint detector to skip ML Kit OCR completely when the cropped ROI
 * region is visually identical to the previous frame, and to monitor unmasked / outer-edge
 * pixels while In-Place Blur is active without hiding the overlay.
 */
class RoiFrameDiffer(
    private val gridCols: Int = 16,
    private val gridRows: Int = 8,
    private val maxAvgLuminanceDelta: Float = 1.2f,
    private val maxPeakLuminanceDelta: Int = 8
) {
    private var previousSample: IntArray? = null
    private var maskedBaselineSample: IntArray? = null
    private var lastMaskSignature: Int = 0

    fun hasVisualChange(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        val totalPoints = gridCols * gridRows
        val currentSample = IntArray(totalPoints)
        var idx = 0

        for (r in 0 until gridRows) {
            val py = ((r + 0.5f) * h / gridRows).toInt().coerceIn(0, h - 1)
            for (c in 0 until gridCols) {
                val px = ((c + 0.5f) * w / gridCols).toInt().coerceIn(0, w - 1)
                currentSample[idx++] = sampleLuma(bitmap, px, py)
            }
        }

        val prev = previousSample
        if (prev == null || prev.size != currentSample.size) {
            previousSample = currentSample
            return true
        }

        val changed = exceedsDelta(prev, currentSample, maxAvgLuminanceDelta, maxPeakLuminanceDelta)
        if (changed) {
            previousSample = currentSample
        }
        return changed
    }

    /**
     * Checks ONLY pixels outside [maskedRects] (plus a 2px-inset ROI perimeter ring) while the
     * In-Place Blur overlay remains visible on screen.
     *
     * On the very first call for a given [maskSignature] (right after a new blur mask is drawn),
     * records the baseline and returns `false` so the static overlay does not flicker immediately.
     * Returns `true` only when outer/edge game pixels actually change, triggering a single
     * stealth capture pulse.
     */
    fun hasUnmaskedVisualChange(
        bitmap: Bitmap,
        maskedRects: List<RoiRect>,
        maskSignature: Int
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        val points = ArrayList<Int>(gridCols * gridRows + 24)
        for (r in 0 until gridRows) {
            val py = ((r + 0.5f) * h / gridRows).toInt().coerceIn(0, h - 1)
            for (c in 0 until gridCols) {
                val px = ((c + 0.5f) * w / gridCols).toInt().coerceIn(0, w - 1)
                if (!isInsideAny(px, py, maskedRects)) {
                    points.add(sampleLuma(bitmap, px, py))
                }
            }
        }

        // Always include outer 2px-inset perimeter ring pixels so edge transitions are caught even if
        // the blur box covers most of the center of the ROI.
        val edgeInsetX = 2.coerceAtMost(w - 1)
        val edgeInsetY = 2.coerceAtMost(h - 1)
        val edgeSteps = 8
        for (i in 0 until edgeSteps) {
            val px = ((i + 0.5f) * w / edgeSteps).toInt().coerceIn(0, w - 1)
            if (!isInsideAny(px, edgeInsetY, maskedRects)) {
                points.add(sampleLuma(bitmap, px, edgeInsetY))
            }
            val bottomY = (h - 1 - edgeInsetY).coerceIn(0, h - 1)
            if (!isInsideAny(px, bottomY, maskedRects)) {
                points.add(sampleLuma(bitmap, px, bottomY))
            }
        }

        // Fallback if blur box covers 100% of ROI up to the exact border: still watch perimeter ring
        if (points.isEmpty()) {
            for (i in 0 until edgeSteps) {
                val px = ((i + 0.5f) * w / edgeSteps).toInt().coerceIn(0, w - 1)
                points.add(sampleLuma(bitmap, px, edgeInsetY))
                points.add(sampleLuma(bitmap, px, (h - 1 - edgeInsetY).coerceIn(0, h - 1)))
            }
        }

        val current = IntArray(points.size) { points[it] }
        val prev = maskedBaselineSample
        if (prev == null || maskSignature != lastMaskSignature || prev.size != current.size) {
            maskedBaselineSample = current
            lastMaskSignature = maskSignature
            return false
        }

        // Tolerate subtle 1-2 pixel background sparkles/particles; trigger only on genuine
        // scene/dialogue changes (meaningful average shift OR >=14% of sampled points shifted).
        val changed = exceedsUnmaskedDelta(
            prev = prev,
            current = current,
            avgThreshold = 4.2f,
            pointDeltaThreshold = 16,
            changedRatioThreshold = 0.14f
        )
        if (changed) {
            maskedBaselineSample = current
        }
        return changed
    }

    fun resetMaskedBaseline() {
        maskedBaselineSample = null
        lastMaskSignature = 0
    }

    fun reset() {
        previousSample = null
        resetMaskedBaseline()
    }

    private fun isInsideAny(px: Int, py: Int, rects: List<RoiRect>): Boolean {
        for (r in rects) {
            if (px >= r.x && px <= r.x + r.width && py >= r.y && py <= r.y + r.height) {
                return true
            }
        }
        return false
    }

    private fun sampleLuma(bitmap: Bitmap, px: Int, py: Int): Int {
        val pixel = bitmap.getPixel(px, py)
        return (Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10
    }

    private fun exceedsDelta(
        prev: IntArray,
        current: IntArray,
        avgThreshold: Float,
        peakThreshold: Int
    ): Boolean {
        var sumDelta = 0
        var peakDelta = 0
        for (i in current.indices) {
            val delta = abs(current[i] - prev[i])
            sumDelta += delta
            if (delta > peakDelta) peakDelta = delta
        }
        val avgDelta = sumDelta.toFloat() / current.size.coerceAtLeast(1)
        return avgDelta > avgThreshold || peakDelta > peakThreshold
    }

    private fun exceedsUnmaskedDelta(
        prev: IntArray,
        current: IntArray,
        avgThreshold: Float,
        pointDeltaThreshold: Int,
        changedRatioThreshold: Float
    ): Boolean {
        var sumDelta = 0
        var changedCount = 0
        val count = current.size.coerceAtLeast(1)
        for (i in current.indices) {
            val delta = abs(current[i] - prev[i])
            sumDelta += delta
            if (delta > pointDeltaThreshold) {
                changedCount++
            }
        }
        val avgDelta = sumDelta.toFloat() / count
        val changedRatio = changedCount.toFloat() / count
        return avgDelta > avgThreshold || changedRatio >= changedRatioThreshold
    }
}
