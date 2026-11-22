package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Lightweight 64-point pixel fingerprint detector to skip ML Kit OCR completely
 * when the cropped ROI region is visually identical to the previous frame.
 */
class RoiFrameDiffer(
    private val gridCols: Int = 16,
    private val gridRows: Int = 8,
    private val maxAvgLuminanceDelta: Float = 1.2f,
    private val maxPeakLuminanceDelta: Int = 8
) {
    private var previousSample: IntArray? = null

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
                val pixel = bitmap.getPixel(px, py)
                // Fast perceptual luminance approximation
                val luma = (Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10
                currentSample[idx++] = luma
            }
        }

        val prev = previousSample
        if (prev == null || prev.size != currentSample.size) {
            previousSample = currentSample
            return true
        }

        var sumDelta = 0
        var peakDelta = 0
        for (i in currentSample.indices) {
            val delta = abs(currentSample[i] - prev[i])
            sumDelta += delta
            if (delta > peakDelta) peakDelta = delta
        }

        val avgDelta = sumDelta.toFloat() / totalPoints
        val changed = avgDelta > maxAvgLuminanceDelta || peakDelta > maxPeakLuminanceDelta
        if (changed) {
            previousSample = currentSample
        }
        return changed
    }

    fun reset() {
        previousSample = null
    }
}
