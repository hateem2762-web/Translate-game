package com.example.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.example.capture.ScreenMetricsHelper
import com.example.model.DisplayMode
import com.example.model.RoiRect
import com.example.model.TranslatorTelemetry

/**
 * Coordinates the ROI selector window (and its In-Place render layer) plus the floating HUD pill.
 */
class OverlayController(
    private val context: Context,
    private val onRoiChanged: (RoiRect) -> Unit,
    private val onToggleDisplayMode: () -> Unit,
    private val onToggleEngineMode: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onToggleLockRoi: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onStopService: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var roiView: RoiOverlayView? = null
    private var roiParams: WindowManager.LayoutParams? = null

    private var hudView: SubtitleHudOverlayView? = null
    private var hudParams: WindowManager.LayoutParams? = null

    private var currentRoi: RoiRect = RoiRect.defaultForScreen(1080, 1920)
    private var currentDisplayMode: DisplayMode? = null
    private var isShown = false

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    fun show(initialRoi: RoiRect, initialOpacity: Float) {
        if (isShown) return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        currentRoi = initialRoi.clampToScreen(bounds.width, bounds.height)

        val roiOverlay = RoiOverlayView(
            context = context,
            onDragDelta = { dx, dy -> moveRoiWindow(dx, dy) },
            onResizeDelta = { dw, dh -> resizeRoiWindow(dw, dh) },
            onInteractionFinished = { actualRect ->
                currentRoi = actualRect
                onRoiChanged(actualRect)
            }
        )

        val topBarOffset = roiOverlay.getTopBarHeightPx()
        val rParams = WindowManager.LayoutParams(
            currentRoi.width,
            currentRoi.height + topBarOffset,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentRoi.x
            y = (currentRoi.y - topBarOffset).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val hudWidth = (bounds.width * 0.90f).toInt().coerceAtLeast(320)
        val hudOverlay = SubtitleHudOverlayView(
            context = context,
            onDragDelta = { dx, dy -> moveHudWindow(dx, dy) },
            onToggleDisplayMode = onToggleDisplayMode,
            onToggleEngineMode = onToggleEngineMode,
            onTogglePause = onTogglePause,
            onToggleLockRoi = onToggleLockRoi,
            onOpenSettings = onOpenSettings,
            onStopService = onStopService,
            onMenuExpansionChanged = { syncHudWindowLayout(DisplayMode.IN_PLACE_BLUR) }
        ).apply {
            updateOpacity(initialOpacity)
        }

        val defaultHudY = if (currentRoi.y > bounds.height * 0.35f) {
            (currentRoi.y - topBarOffset - dp(124f)).coerceAtLeast(dp(40f))
        } else {
            (currentRoi.y + currentRoi.height + dp(24f)).coerceAtMost(bounds.height - dp(140f))
        }

        val hParams = WindowManager.LayoutParams(
            hudWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width - hudWidth) / 2).coerceAtLeast(0)
            y = defaultHudY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(roiOverlay, rParams)
        windowManager.addView(hudOverlay, hParams)

        roiView = roiOverlay
        roiParams = rParams
        hudView = hudOverlay
        hudParams = hParams
        isShown = true
    }

    fun getActiveCaptureRoi(): RoiRect {
        val view = roiView ?: return currentRoi
        if (!view.isAttachedToWindow || view.width == 0 || view.height == 0) {
            return currentRoi
        }
        return view.getCaptureRectOnScreen()
    }

    fun hasActiveInPlaceOverlay(): Boolean {
        return roiView?.hasActiveInPlaceBlocks() == true
    }

    fun setInPlaceStealth(hidden: Boolean) {
        roiView?.setInPlaceStealth(hidden)
    }

    fun setRoiLocked(locked: Boolean) {
        val view = roiView ?: return
        val params = roiParams ?: return
        view.setLocked(locked)

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.flags = if (locked) {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            baseFlags
        }
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    fun updateHud(telemetry: TranslatorTelemetry, opacity: Float, showOriginal: Boolean) {
        val modeChanged = currentDisplayMode != telemetry.displayMode
        currentDisplayMode = telemetry.displayMode

        hudView?.let {
            it.updateOpacity(opacity)
            it.render(telemetry, showOriginal)
        }
        if (modeChanged) {
            syncHudWindowLayout(telemetry.displayMode)
        }
        roiView?.let { roi ->
            if (telemetry.displayMode == DisplayMode.IN_PLACE_BLUR) {
                roi.updateInPlaceBlocks(telemetry.inPlaceBlocks, telemetry.fontSize)
            } else {
                roi.updateInPlaceBlocks(emptyList(), telemetry.fontSize)
            }
        }
    }

    private fun syncHudWindowLayout(mode: DisplayMode) {
        val view = hudView ?: return
        val params = hudParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        if (mode == DisplayMode.IN_PLACE_BLUR) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            // Tuck compact circular button cleanly toward top-right corner when entering In-Place mode
            val fabEdgeMargin = dp(12f)
            val approxWidth = if (view.isCornerMenuOpen()) dp(290f) else dp(52f)
            params.x = (bounds.width - approxWidth - fabEdgeMargin).coerceAtLeast(fabEdgeMargin)
            params.y = params.y.coerceIn(dp(36f), (bounds.height - dp(96f)).coerceAtLeast(dp(36f)))
        } else {
            val classicWidth = (bounds.width * 0.90f).toInt().coerceAtLeast(320)
            params.width = classicWidth
            params.x = ((bounds.width - classicWidth) / 2).coerceAtLeast(0)
            params.y = params.y.coerceIn(dp(36f), (bounds.height - dp(160f)).coerceAtLeast(dp(36f)))
        }
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun moveRoiWindow(dx: Int, dy: Int) {
        val view = roiView ?: return
        val params = roiParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        params.x = (params.x + dx).coerceIn(0, (bounds.width - params.width).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(0, (bounds.height - params.height).coerceAtLeast(0))
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun resizeRoiWindow(dw: Int, dh: Int) {
        val view = roiView ?: return
        val params = roiParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val minW = dp(140f)
        val minH = dp(64f) + view.getTopBarHeightPx()
        params.width = (params.width + dw).coerceIn(minW, (bounds.width - params.x).coerceAtLeast(minW))
        params.height = (params.height + dh).coerceIn(minH, (bounds.height - params.y).coerceAtLeast(minH))
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun moveHudWindow(dx: Int, dy: Int) {
        val view = hudView ?: return
        val params = hudParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val viewW = if (params.width > 0) params.width else view.width.coerceAtLeast(dp(48f))
        val viewH = view.height.coerceAtLeast(dp(48f))
        params.x = (params.x + dx).coerceIn(0, (bounds.width - viewW).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(0, (bounds.height - viewH).coerceAtLeast(0))
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    fun hide() {
        if (!isShown) return
        roiView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        hudView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        roiView = null
        roiParams = null
        hudView = null
        hudParams = null
        isShown = false
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
}
