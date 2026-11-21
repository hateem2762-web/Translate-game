package com.example.model

/**
 * Screen-space Region of Interest (ROI) rectangle in physical pixels.
 */
data class RoiRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    fun clampToScreen(screenWidth: Int, screenHeight: Int, minWidth: Int = 180, minHeight: Int = 90): RoiRect {
        if (screenWidth <= 0 || screenHeight <= 0) return this
        val safeW = width.coerceIn(minWidth.coerceAtMost(screenWidth), screenWidth)
        val safeH = height.coerceIn(minHeight.coerceAtMost(screenHeight), screenHeight)
        val safeX = x.coerceIn(0, (screenWidth - safeW).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (screenHeight - safeH).coerceAtLeast(0))
        return RoiRect(safeX, safeY, safeW, safeH)
    }

    companion object {
        fun defaultForScreen(screenWidth: Int, screenHeight: Int): RoiRect {
            val w = (screenWidth * 0.78f).toInt().coerceAtLeast(240)
            val h = (screenHeight * 0.14f).toInt().coerceAtLeast(130)
            val x = ((screenWidth - w) / 2).coerceAtLeast(0)
            val y = (screenHeight * 0.70f).toInt().coerceIn(0, (screenHeight - h).coerceAtLeast(0))
            return RoiRect(x, y, w, h)
        }
    }
}
