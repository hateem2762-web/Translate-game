package com.example.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.capture.ScreenMetricsHelper
import com.example.model.AiProviderPreset
import com.example.model.DisplayMode
import com.example.model.FloatingBubbleBehavior
import com.example.model.FloatingBubbleTheme
import com.example.model.OverlayHubStage
import com.example.model.RoiRect
import com.example.model.SubtitleEntry
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.example.model.TranslatorTelemetry
import kotlin.math.roundToInt

/**
 * Coordinates the three live overlay layers:
 * 1. ROI viewfinder + In-Place frosted Blur canvas ([RoiOverlayView]) — visible in ACTIVE_MODE
 * 2. Active-mode HUD ([SubtitleHudOverlayView]) — Classic Box OR In-Place Blur corner "X" close button
 * 3. Floating Control Bubble / Mode Picker / Half-Circle "<" Dock ([FloatingControlBubbleOverlayView])
 * Plus modal popups ([DialogueHistoryOverlayView] and [ModelPickerOverlayView]).
 */
class OverlayController(
    private val context: Context,
    private val onRoiChanged: (RoiRect) -> Unit,
    private val onClassicBoxLayoutChanged: (x: Int, y: Int, widthDp: Int, heightDp: Int) -> Unit,
    private val onSelectTranslationMode: (DisplayMode) -> Unit,
    private val onCloseActiveMode: () -> Unit,
    private val onToggleDisplayMode: () -> Unit,
    private val onSelectEngineMode: (TranslationEngineMode) -> Unit,
    private val onOpenEnginePicker: () -> Unit,
    private val onOpenModelPicker: () -> Unit,
    private val onSelectAiPresetFromOverlay: (AiProviderPreset) -> Unit,
    private val onSelectAiModelFromOverlay: (AiProviderPreset, String) -> Unit,
    private val onRefreshModelsFromOverlay: (AiProviderPreset) -> Unit,
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

    private var bubbleView: FloatingControlBubbleOverlayView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var historyView: DialogueHistoryOverlayView? = null
    private var modelPickerView: ModelPickerOverlayView? = null
    private var enginePickerView: EnginePickerOverlayView? = null

    private var currentRoi: RoiRect = RoiRect.defaultForScreen(1080, 1920)
    private var currentDisplayMode: DisplayMode? = null
    private var currentRoiLocked: Boolean = false
    private var currentHubStage: OverlayHubStage = OverlayHubStage.BUBBLE_IDLE
    private var currentBubbleBehavior: FloatingBubbleBehavior? = null
    private var currentBubbleTheme: FloatingBubbleTheme? = null
    private var lastBubblePresentation: FloatingControlBubbleOverlayView.VisualPresentation? = null

    private var appliedClassicWidthDp: Int = -1
    private var appliedClassicHeightDp: Int = -1
    private var desiredClassicHeightPx: Int = -1
    private var isResizingClassicBox: Boolean = false
    private var savedClassicX: Int = -1
    private var savedClassicY: Int = -1

    private var savedBubbleX: Int = -1
    private var savedBubbleY: Int = -1
    private var savedDockY: Int = -1

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

        savedBubbleX = (bounds.width - dp(74f)).coerceAtLeast(dp(12f))
        savedBubbleY = (bounds.height * 0.44f).toInt().coerceIn(dp(48f), (bounds.height - dp(96f)).coerceAtLeast(dp(48f)))
        savedDockY = (bounds.height * 0.74f).toInt().coerceIn(dp(64f), (bounds.height - dp(96f)).coerceAtLeast(dp(64f)))

        // 1. ROI Overlay (initially GONE in BUBBLE_IDLE until user chooses a mode)
        val roiOverlay = RoiOverlayView(
            context = context,
            onDragDelta = { dx, dy -> moveRoiWindow(dx, dy) },
            onResizeDelta = { dw, dh -> resizeRoiWindow(dw, dh) },
            onInteractionFinished = { actualRect ->
                currentRoi = actualRect
                onRoiChanged(actualRect)
            }
        ).apply {
            setLocked(false, hideFrameWhenLocked = initialConfig.displayMode == DisplayMode.IN_PLACE_BLUR)
            visibility = View.GONE
        }

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

        // 2. Active-Mode HUD Overlay (initially GONE in BUBBLE_IDLE)
        val hudOverlay = SubtitleHudOverlayView(
            context = context,
            onDragDelta = { dx, dy -> moveHudWindow(dx, dy) },
            onDragFinished = { commitClassicBoxLayoutIfActive() },
            onClassicResizeDelta = { dw, dh ->
                isResizingClassicBox = true
                resizeClassicBoxWindow(dw, dh)
            },
            onClassicResizeFinished = {
                isResizingClassicBox = false
                commitClassicBoxLayoutIfActive()
            },
            onClassicDesiredHeightChanged = { desiredPx ->
                applyDynamicClassicHeight(desiredPx)
            },
            onToggleDisplayMode = onToggleDisplayMode,
            onOpenEnginePicker = onOpenEnginePicker,
            onOpenModelPicker = onOpenModelPicker,
            onTogglePause = onTogglePause,
            onToggleLockRoi = onToggleLockRoi,
            onOpenHistory = onOpenHistory,
            onCloseActiveMode = onCloseActiveMode
        ).apply {
            updateClassicBoxOpacity(initialConfig.classicBoxOpacity)
            visibility = View.GONE
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

        // 3. Draggable Floating Control Bubble + Mode Menu + Half-Circle "<" Dock
        val bubbleOverlay = FloatingControlBubbleOverlayView(
            context = context,
            onDragBubbleDelta = { dx, dy -> moveBubbleWindow(dx, dy) },
            onDragBubbleFinished = { persistBubbleAnchor() },
            onDragDockVerticalDelta = { dy -> moveDockVertically(dy) },
            onDragDockFinished = { persistDockAnchor() },
            onSelectTranslationMode = onSelectTranslationMode,
            onCloseActiveMode = onCloseActiveMode,
            onSelectEngineMode = onSelectEngineMode,
            onOpenModelPicker = onOpenModelPicker,
            onTogglePause = onTogglePause,
            onToggleLockRoi = onToggleLockRoi,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
            onStopService = onStopService,
            onLayoutRequirementChanged = { syncBubbleWindowLayout() }
        )

        val bParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedBubbleX
            y = savedBubbleY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(roiOverlay, rParams)
        windowManager.addView(hudOverlay, hParams)
        windowManager.addView(bubbleOverlay, bParams)

        roiView = roiOverlay
        roiParams = rParams
        hudView = hudOverlay
        hudParams = hParams
        bubbleView = bubbleOverlay
        bubbleParams = bParams
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
        return currentHubStage == OverlayHubStage.ACTIVE_MODE &&
            (roiView?.hasActiveInPlaceBlocks() == true || hudView?.hasInPlaceError() == true)
    }

    fun getActiveMaskRectsInRoi(): Pair<List<RoiRect>, Int> {
        return roiView?.getActiveMaskRectsInRoi() ?: (emptyList<RoiRect>() to 0)
    }

    fun getInPlaceControlsRectInRoi(): RoiRect? {
        if (currentHubStage != OverlayHubStage.ACTIVE_MODE ||
            currentDisplayMode != DisplayMode.IN_PLACE_BLUR
        ) {
            return null
        }
        val ctrlScreen = hudView?.getInPlaceControlsScreenRect() ?: return null
        val roi = getActiveCaptureRoi()
        val padPx = dp(6f)
        val left = (ctrlScreen.x - roi.x - padPx).coerceIn(0, roi.width)
        val top = (ctrlScreen.y - roi.y - padPx).coerceIn(0, roi.height)
        val right = (ctrlScreen.x + ctrlScreen.width - roi.x + padPx).coerceIn(left, roi.width)
        val bottom = (ctrlScreen.y + ctrlScreen.height - roi.y + padPx).coerceIn(top, roi.height)
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return null
        return RoiRect(x = left, y = top, width = w, height = h)
    }

    fun setInPlaceStealth(hidden: Boolean) {
        roiView?.setInPlaceStealth(hidden)
        hudView?.setInPlaceStealth(hidden)
    }

    suspend fun hideInPlaceAndAwaitCommitNs(): Long {
        hudView?.setInPlaceStealth(true)
        return roiView?.awaitHiddenCommittedNs() ?: System.nanoTime()
    }

    fun isHistoryVisible(): Boolean = historyView != null || modelPickerView != null || enginePickerView != null

    fun showHistory(entries: List<SubtitleEntry>) {
        if (!isShown) return
        hideModelPicker()
        hideEnginePicker()
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

    fun showModelPicker(telemetry: TranslatorTelemetry) {
        if (!isShown) return
        hideHistory()
        hideEnginePicker()
        val existing = modelPickerView
        if (existing != null) {
            existing.render(
                preset = telemetry.aiPreset,
                modelId = telemetry.aiModelName,
                configured = telemetry.configuredPresets,
                catalogByPreset = telemetry.availableModelsByPreset
            )
            return
        }

        val picker = ModelPickerOverlayView(
            context = context,
            onSelectPreset = onSelectAiPresetFromOverlay,
            onSelectModel = onSelectAiModelFromOverlay,
            onRefreshModels = onRefreshModelsFromOverlay,
            onDismiss = { hideModelPicker() }
        ).apply {
            render(
                preset = telemetry.aiPreset,
                modelId = telemetry.aiModelName,
                configured = telemetry.configuredPresets,
                catalogByPreset = telemetry.availableModelsByPreset
            )
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

        windowManager.addView(picker, params)
        modelPickerView = picker
    }

    fun updateModelPickerState(telemetry: TranslatorTelemetry, refreshing: Boolean, note: String?) {
        modelPickerView?.render(
            preset = telemetry.aiPreset,
            modelId = telemetry.aiModelName,
            configured = telemetry.configuredPresets,
            catalogByPreset = telemetry.availableModelsByPreset,
            refreshing = refreshing,
            note = note
        )
    }

    fun hideModelPicker() {
        modelPickerView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        modelPickerView = null
    }

    fun showEnginePicker(telemetry: TranslatorTelemetry) {
        if (!isShown) return
        hideHistory()
        hideModelPicker()
        val existing = enginePickerView
        if (existing != null) {
            existing.render(telemetry.engineMode, telemetry.aiPreset, telemetry.aiModelName)
            return
        }

        val picker = EnginePickerOverlayView(
            context = context,
            onSelectEngine = onSelectEngineMode,
            onOpenModelPicker = onOpenModelPicker,
            onDismiss = { hideEnginePicker() }
        ).apply {
            render(telemetry.engineMode, telemetry.aiPreset, telemetry.aiModelName)
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

        windowManager.addView(picker, params)
        enginePickerView = picker
    }

    fun hideEnginePicker() {
        enginePickerView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        enginePickerView = null
    }

    fun setRoiLocked(locked: Boolean) {
        currentRoiLocked = locked
        val view = roiView ?: return
        val params = roiParams ?: return
        val hideFrame = currentDisplayMode == DisplayMode.IN_PLACE_BLUR
        view.setLocked(locked, hideFrameWhenLocked = hideFrame)

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
        val stageChanged = currentHubStage != telemetry.overlayHubStage
        val modeChanged = currentDisplayMode != telemetry.displayMode
        val behaviorChanged = currentBubbleBehavior != telemetry.bubbleBehavior
        val themeChanged = currentBubbleTheme != telemetry.bubbleTheme
        val lockChanged = currentRoiLocked != telemetry.isRoiLocked

        currentHubStage = telemetry.overlayHubStage
        currentDisplayMode = telemetry.displayMode
        currentBubbleBehavior = telemetry.bubbleBehavior
        currentBubbleTheme = telemetry.bubbleTheme

        if (lockChanged || modeChanged) {
            setRoiLocked(telemetry.isRoiLocked)
        }

        savedClassicX = telemetry.classicBoxX
        savedClassicY = telemetry.classicBoxY

        val classicSizeChanged = telemetry.classicBoxWidthDp != appliedClassicWidthDp ||
            telemetry.classicBoxHeightDp != appliedClassicHeightDp
        appliedClassicWidthDp = telemetry.classicBoxWidthDp
        appliedClassicHeightDp = telemetry.classicBoxHeightDp

        val isActive = telemetry.overlayHubStage == OverlayHubStage.ACTIVE_MODE

        // ROI & Blur visibility
        roiView?.let { roi ->
            val targetRoiVis = if (isActive) View.VISIBLE else View.GONE
            if (roi.visibility != targetRoiVis) {
                roi.visibility = targetRoiVis
            }
            roi.setLocked(
                locked = telemetry.isRoiLocked,
                hideFrameWhenLocked = telemetry.displayMode == DisplayMode.IN_PLACE_BLUR
            )
            val style = InPlaceRenderStyle.fromTelemetry(telemetry)
            if (isActive && telemetry.displayMode == DisplayMode.IN_PLACE_BLUR) {
                roi.updateInPlaceBlocks(telemetry.inPlaceBlocks, style)
            } else {
                roi.updateInPlaceBlocks(emptyList(), style)
            }
        }

        val hadInPlaceError = hudView?.hasInPlaceError() == true

        // Active-Mode HUD (Classic Box OR In-Place Blur inner corner Lock + X controls)
        hudView?.let { hud ->
            val targetHudVis = if (isActive) View.VISIBLE else View.GONE
            if (hud.visibility != targetHudVis) {
                hud.visibility = targetHudVis
            }
            hud.updateClassicBoxOpacity(telemetry.classicBoxOpacity)
            hud.render(telemetry, showOriginal)
        }
        val hasInPlaceError = hudView?.hasInPlaceError() == true
        if (isActive && (
                stageChanged ||
                    modeChanged ||
                    (telemetry.displayMode == DisplayMode.CLASSIC_BOX && classicSizeChanged) ||
                    (telemetry.displayMode == DisplayMode.IN_PLACE_BLUR && hadInPlaceError != hasInPlaceError)
                )
        ) {
            syncHudWindowLayout(telemetry.displayMode)
        }

        // Floating Control Bubble / Half-Circle Dock
        bubbleView?.let { bubble ->
            bubble.render(telemetry)
            val presentationChanged = lastBubblePresentation != bubble.currentPresentation()
            if (stageChanged || behaviorChanged || themeChanged || presentationChanged) {
                syncBubbleWindowLayout()
            }
        }

        historyView?.renderEntries(telemetry.recentEntries, scrollToLatest = false)
        modelPickerView?.render(
            preset = telemetry.aiPreset,
            modelId = telemetry.aiModelName,
            configured = telemetry.configuredPresets,
            catalogByPreset = telemetry.availableModelsByPreset
        )
        enginePickerView?.render(
            engineMode = telemetry.engineMode,
            aiPreset = telemetry.aiPreset,
            aiModelName = telemetry.aiModelName
        )
    }

    private fun syncBubbleWindowLayout() {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val presentation = bubble.currentPresentation()
        lastBubblePresentation = presentation

        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        when (presentation) {
            FloatingControlBubbleOverlayView.VisualPresentation.BUBBLE_ONLY -> {
                val bubbleW = dp(62f)
                val bubbleH = dp(62f)
                params.x = savedBubbleX.coerceIn(dp(6f), (bounds.width - bubbleW - dp(6f)).coerceAtLeast(dp(6f)))
                params.y = savedBubbleY.coerceIn(dp(24f), (bounds.height - bubbleH - dp(16f)).coerceAtLeast(dp(24f)))
            }

            FloatingControlBubbleOverlayView.VisualPresentation.MENU_WITH_BUBBLE -> {
                val menuApproxW = dp(332f)
                val menuApproxH = dp(430f)
                val anchorY = if (currentHubStage == OverlayHubStage.ACTIVE_MODE &&
                    currentBubbleBehavior == FloatingBubbleBehavior.DOCK_HALF_CIRCLE
                ) {
                    savedDockY - menuApproxH + dp(66f)
                } else {
                    savedBubbleY - menuApproxH + dp(66f)
                }
                val anchorX = if (currentHubStage == OverlayHubStage.ACTIVE_MODE &&
                    currentBubbleBehavior == FloatingBubbleBehavior.DOCK_HALF_CIRCLE
                ) {
                    bounds.width - menuApproxW - dp(8f)
                } else {
                    savedBubbleX - dp(244f)
                }
                params.x = anchorX.coerceIn(dp(6f), (bounds.width - menuApproxW - dp(6f)).coerceAtLeast(dp(6f)))
                params.y = anchorY.coerceIn(dp(24f), (bounds.height - menuApproxH - dp(12f)).coerceAtLeast(dp(24f)))
            }

            FloatingControlBubbleOverlayView.VisualPresentation.HALF_CIRCLE_DOCK -> {
                val dockW = dp(34f)
                val dockH = dp(60f)
                params.x = (bounds.width - dockW).coerceAtLeast(0)
                params.y = savedDockY.coerceIn(dp(48f), (bounds.height - dockH - dp(24f)).coerceAtLeast(dp(48f)))
            }

            FloatingControlBubbleOverlayView.VisualPresentation.HIDDEN -> Unit
        }

        if (bubble.isAttachedToWindow) {
            windowManager.updateViewLayout(bubble, params)
        }
    }

    private fun syncHudWindowLayout(mode: DisplayMode) {
        val view = hudView ?: return
        val params = hudParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        if (mode == DisplayMode.IN_PLACE_BLUR) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            val rParams = roiParams
            val rTopBar = roiView?.getTopBarHeightPx() ?: dp(28f)
            val containerW = if (view.hasInPlaceError()) dp(216f) else dp(48f)
            val targetX = if (rParams != null) {
                (rParams.x + rParams.width - containerW - dp(6f))
                    .coerceIn(dp(4f), (bounds.width - containerW - dp(4f)).coerceAtLeast(dp(4f)))
            } else {
                (bounds.width - containerW - dp(8f)).coerceAtLeast(dp(4f))
            }
            val targetY = if (rParams != null) {
                val boxTop = rParams.y + rTopBar
                val maxYInside = (rParams.y + rParams.height - dp(28f)).coerceAtLeast(boxTop)
                (boxTop + dp(4f)).coerceIn(dp(8f), minOf(maxYInside, (bounds.height - dp(32f)).coerceAtLeast(dp(8f))))
            } else {
                dp(36f)
            }
            params.x = targetX
            params.y = targetY
        } else {
            val targetW = clampClassicWidthPx(dp(appliedClassicWidthDp.coerceAtLeast(200).toFloat()), bounds.width)
            val requestedH = if (desiredClassicHeightPx > 0) desiredClassicHeightPx else dp(appliedClassicHeightDp.coerceAtLeast(84).toFloat())
            val targetH = clampClassicExpandedHeightPx(requestedH, bounds.height)
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

    private fun applyDynamicClassicHeight(requestedHeightPx: Int) {
        if (currentHubStage != OverlayHubStage.ACTIVE_MODE ||
            currentDisplayMode != DisplayMode.CLASSIC_BOX ||
            isResizingClassicBox
        ) return
        val view = hudView ?: return
        val params = hudParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val clampedH = clampClassicExpandedHeightPx(requestedHeightPx, bounds.height)
        desiredClassicHeightPx = clampedH
        if (kotlin.math.abs(params.height - clampedH) <= dp(3f)) return
        params.height = clampedH
        val maxY = (bounds.height - clampedH - dp(12f)).coerceAtLeast(dp(24f))
        if (params.y > maxY) {
            params.y = maxY
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
        if (currentDisplayMode == DisplayMode.IN_PLACE_BLUR) {
            syncHudWindowLayout(DisplayMode.IN_PLACE_BLUR)
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
        if (currentDisplayMode == DisplayMode.IN_PLACE_BLUR) {
            syncHudWindowLayout(DisplayMode.IN_PLACE_BLUR)
        }
    }

    private fun moveBubbleWindow(dx: Int, dy: Int) {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val w = bubble.width.coerceAtLeast(dp(62f))
        val h = bubble.height.coerceAtLeast(dp(62f))
        params.x = (params.x + dx).coerceIn(0, (bounds.width - w).coerceAtLeast(0))
        params.y = (params.y + dy).coerceIn(dp(16f), (bounds.height - h - dp(12f)).coerceAtLeast(dp(16f)))
        if (bubble.currentPresentation() == FloatingControlBubbleOverlayView.VisualPresentation.BUBBLE_ONLY) {
            savedBubbleX = params.x
            savedBubbleY = params.y
        }
        if (bubble.isAttachedToWindow) {
            windowManager.updateViewLayout(bubble, params)
        }
    }

    private fun persistBubbleAnchor() {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        if (bubble.currentPresentation() == FloatingControlBubbleOverlayView.VisualPresentation.BUBBLE_ONLY) {
            savedBubbleX = params.x
            savedBubbleY = params.y
        }
    }

    private fun moveDockVertically(dy: Int) {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val dockH = dp(60f)
        params.y = (params.y + dy).coerceIn(dp(48f), (bounds.height - dockH - dp(24f)).coerceAtLeast(dp(48f)))
        savedDockY = params.y
        if (bubble.isAttachedToWindow) {
            windowManager.updateViewLayout(bubble, params)
        }
    }

    private fun persistDockAnchor() {
        val params = bubbleParams ?: return
        savedDockY = params.y
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
        hideModelPicker()
        hideEnginePicker()
        roiView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        hudView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        bubbleView?.let {
            if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
        }
        roiView = null
        roiParams = null
        hudView = null
        hudParams = null
        bubbleView = null
        bubbleParams = null
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

    private fun clampClassicExpandedHeightPx(requestedPx: Int, screenHeight: Int): Int {
        val minPx = dp(84f)
        val maxPx = minOf(dp(420f), (screenHeight * 0.56f).toInt()).coerceAtLeast(minPx)
        return requestedPx.coerceIn(minPx, maxPx)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun pxToDp(px: Int): Int = (px / context.resources.displayMetrics.density).roundToInt()
}
