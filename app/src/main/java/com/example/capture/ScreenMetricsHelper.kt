package com.example.capture

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

data class ScreenBounds(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

object ScreenMetricsHelper {
    fun getScreenBounds(context: Context): ScreenBounds {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = context.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            ScreenBounds(
                width = bounds.width().coerceAtLeast(1),
                height = bounds.height().coerceAtLeast(1),
                densityDpi = densityDpi
            )
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            ScreenBounds(
                width = metrics.widthPixels.coerceAtLeast(1),
                height = metrics.heightPixels.coerceAtLeast(1),
                densityDpi = metrics.densityDpi
            )
        }
    }
}
