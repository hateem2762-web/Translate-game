package com.example.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.example.capture.ScreenMetricsHelper
import com.example.model.DisplayMode
import com.example.model.RoiRect
import com.example.model.SubtitleEntry
import com.example.model.TranslationConfig
import com.example.model.TranslatorTelemetry
import kotlin.math.roundToInt

/**
 * Coordinates the ROI selector window (and its In-Place render layer), the floating HUD window
 * (resizable & persisted Classic Box OR compact In-Place floating circular button), and the
 * floating Dialogue History popup window.
 */
class OverlayController(
    private val context: Context,
    private val onRoiChanged: (RoiRect) -> Unit,
    private val onClassicBoxLayoutChanged: (x: Int, y: Int, widthDp: Int, heightDp: Int) -> Unit,
    private val onToggleDisplayMode: () -> Unit,
    private val onToggleEngineMode: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onToggleLockRoi: () -> Unit,
    private val onOpenHistory: () -> Unit,
    private val onClearHistory: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onStopService: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var roiView: RoiOverlayView? = null
    private var roiParams: WindowManager.LayoutParams? = null

    private var hudView: SubtitleHudOverlayView? = null
    private var hudParams: WindowManager.LayoutParams? = null

    private var historyView: DialogueHistoryOverlayView? = null

    private var currentRoi: RoiRect = RoiRect.defaultForScreen(1080, 1920)
    private var currentDisplayMode: DisplayMode? = null
    private var appliedClassicWidthDp: Int = -1
    private var appliedClassicHeightDp: Int = -1
    private var savedClassicX: Int = -1
    private var savedClassicY: Int = -1
    private var isShown = false

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    fun show(initialRoi: RoiRect, initialConfig: TranslationConfig) {
        if (isShown) return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        currentRoi = initialRoi.clampToScreen(bounds.width, bounds.height)
        appliedClassicWidthDp = initialConfig.classicBoxWidthDp
        appliedClassicHeightDp = initialConfig.classicBoxHeightDp
        savedClassicX = initialConfig.classicBoxX
        savedClassicY = initialConfig.classicBoxY

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

        val hudOverlay = SubtitleHudOverlayView(
            context = context,
            onDragDelta = { dx, dy -> moveHudWindow(dx, dy) },
            onDragFinished = { commitClassicBoxLayoutIfActive() },
            onClassicResizeDelta = { dw, dh -> resizeClassicBoxWindow(dw, dh) },
            onClassicResizeFinished = { commitClassicBoxLayoutIfActive() },
            onToggleDisplayMode = onToggleDisplayMode,
            onToggleEngineMode = onToggleEngineMode,
            onTogglePause = onTogglePause,
            onToggleLockRoi = onToggleLockRoi,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
            onStopService = onStopService,
            onMenuExpansionChanged = { syncHudWindowLayout(DisplayMode.IN_PLACE_BLUR) }
        ).apply {
            updateClassicBoxOpacity(initialConfig.classicBoxOpacity)
        }

        val classicW = clampClassicWidthPx(dp(initialConfig.classicBoxWidthDp.toFloat()), bounds.width)
        val classicH = clampClassicHeightPx(dp(initialConfig.classicBoxHeightDp.toFloat()), bounds.height)
        val defaultHudX = if (savedClassicX >= 0) {
            savedClassicX.coerceIn(0, (bounds.width - classicW).coerceAtLeast(0))
        } else {
            ((bounds.width - classicW) / 2).coerceAtLeast(0)
        }
        val defaultHudY = if (savedClassicY >= 0) {
            savedClassicY.coerceIn(dp(24f), (bounds.height - classicH).coerceAtLeast(dp(24f)))
        } else if (currentRoi.y > bounds.height * 0.35f) {
            (currentRoi.y - topBarOffset - classicH - dp(12f)).coerceAtLeast(dp(40f))
        } else {
            (currentRoi.y + currentRoi.height + dp(20f)).coerceAtMost((bounds.height - classicH - dp(16f)).coerceAtLeast(dp(40f)))
        }

        val hParams = WindowManager.LayoutParams(
            classicW,
            classicH,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultHudX
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

    fun isHistoryVisible(): Boolean = historyView != null

    fun showHistory(entries: List<SubtitleEntry>) {
        if (!isShown) return
        val existing = historyView
        if (existing != null) {
            existing.renderEntries(entries, scrollToLatest = true)
            return
        }

        val popup = DialogueHistoryOverlayView(
            context = context,
            onClearHistory = onClearHistory,
            onDismiss = { hideHistory() }
        ).apply {
            renderEntries(entries, scrollToLatest = true)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(popup, params)
        historyView = popup
    }

    fun hideHistory() {
        historyView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        historyView = null
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

    fun updateHud(telemetry: TranslatorTelemetry, showOriginal: Boolean) {
        val modeChanged = currentDisplayMode != telemetry.displayMode
        currentDisplayMode = telemetry.displayMode

        savedClassicX = telemetry.classicBoxX
        savedClassicY = telemetry.classicBoxY

        val classicSizeChanged = telemetry.classicBoxWidthDp != appliedClassicWidthDp ||
            telemetry.classicBoxHeightDp != appliedClassicHeightDp
        appliedClassicWidthDp = telemetry.classicBoxWidthDp
        appliedClassicHeightDp = telemetry.classicBoxHeightDp

        hudView?.let {
            it.updateClassicBoxOpacity(telemetry.classicBoxOpacity)
            it.render(telemetry, showOriginal)
        }
        if (modeChanged || (telemetry.displayMode == DisplayMode.CLASSIC_BOX && classicSizeChanged)) {
            syncHudWindowLayout(telemetry.displayMode)
        }
        roiView?.let { roi ->
            val style = InPlaceRenderStyle.fromTelemetry(telemetry)
            if (telemetry.displayMode == DisplayMode.IN_PLACE_BLUR) {
                roi.updateInPlaceBlocks(telemetry.inPlaceBlocks, style)
            } else {
                roi.updateInPlaceBlocks(emptyList(), style)
            }
        }
        historyView?.renderEntries(telemetry.recentEntries, scrollToLatest = false)
    }

    private fun syncHudWindowLayout(mode: DisplayMode) {
        val view = hudView ?: return
        val params = hudParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        if (mode == DisplayMode.IN_PLACE_BLUR) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            val fabEdgeMargin = dp(12f)
            val approxWidth = if (view.isCornerMenuOpen()) dp(330f) else dp(52f)
            params.x = (bounds.width - approxWidth - fabEdgeMargin).coerceAtLeast(fabEdgeMargin)
            params.y = params.y.coerceIn(dp(36f), (bounds.height - dp(96f)).coerceAtLeast(dp(36f)))
        } else {
            val targetW = clampClassicWidthPx(dp(appliedClassicWidthDp.coerceAtLeast(200).toFloat()), bounds.width)
            val targetH = clampClassicHeightPx(dp(appliedClassicHeightDp.coerceAtLeast(84).toFloat()), bounds.height)
            params.width = targetW
            params.height = targetH
            params.x = if (savedClassicX >= 0) {
                savedClassicX.coerceIn(0, (bounds.width - targetW).coerceAtLeast(0))
            } else {
                ((bounds.width - targetW) / 2).coerceAtLeast(0)
            }
            params.y = if (savedClassicY >= 0) {
                savedClassicY.coerceIn(dp(24f), (bounds.height - targetH).coerceAtLeast(dp(24f)))
            } else {
                params.y.coerceIn(dp(36f), (bounds.height - targetH - dp(12f)).coerceAtLeast(dp(36f)))
            }
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
        val viewH = if (params.height > 0) params.height else view.height.coerceAtLeast(dp(48f))
        params.x = (params.x + dx).coerceIn(0, (bounds.width - viewW).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(0, (bounds.height - viewH).coerceAtLeast(0))
        if (currentDisplayMode == DisplayMode.CLASSIC_BOX) {
            savedClassicX = params.x
            savedClassicY = params.y
        }
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun resizeClassicBoxWindow(dw: Int, dh: Int) {
        if (currentDisplayMode != DisplayMode.CLASSIC_BOX) return
        val view = hudView ?: return
        val params = hudParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val maxW = (bounds.width - params.x - dp(6f)).coerceAtLeast(dp(200f))
        val maxH = (bounds.height - params.y - dp(12f)).coerceAtLeast(dp(84f))
        val nextW = (params.width + dw).coerceIn(dp(200f), maxW)
        val nextH = (params.height + dh).coerceIn(dp(84f), minOf(maxH, dp(340f)))
        params.width = nextW
        params.height = nextH
        appliedClassicWidthDp = pxToDp(nextW).coerceIn(200, 440)
        appliedClassicHeightDp = pxToDp(nextH).coerceIn(84, 340)
        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun commitClassicBoxLayoutIfActive() {
        if (currentDisplayMode != DisplayMode.CLASSIC_BOX) return
        val params = hudParams ?: return
        val wDp = pxToDp(params.width).coerceIn(200, 440)
        val hDp = pxToDp(params.height).coerceIn(84, 340)
        appliedClassicWidthDp = wDp
        appliedClassicHeightDp = hDp
        savedClassicX = params.x
        savedClassicY = params.y
        onClassicBoxLayoutChanged(params.x, params.y, wDp, hDp)
    }

    fun hide() {
        if (!isShown) return
        hideHistory()
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

    private fun clampClassicWidthPx(requestedPx: Int, screenWidth: Int): Int {
        val minPx = dp(200f)
        val maxPx = (screenWidth - dp(12f)).coerceAtLeast(minPx)
        return requestedPx.coerceIn(minPx, maxPx)
    }

    private fun clampClassicHeightPx(requestedPx: Int, screenHeight: Int): Int {
        val minPx = dp(84f)
        val maxPx = minOf(dp(340f), (screenHeight * 0.60f).toInt()).coerceAtLeast(minPx)
        return requestedPx.coerceIn(minPx, maxPx)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun pxToDp(px: Int): Int = (px / context.resources.displayMetrics.density).roundToInt()
}
