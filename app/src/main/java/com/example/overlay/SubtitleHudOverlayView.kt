package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.ClassicTextFitMode
import com.example.model.DisplayMode
import com.example.model.ServiceStatus
import com.example.model.TranslationEngineMode
import com.example.model.TranslatorTelemetry

/**
 * Active-mode floating HUD:
 * - In CLASSIC_BOX mode: redesigned engine-coded subtitle box with an unmistakable active-engine
 *   pill (tapping opens the direct Engine Picker), an AI-model chip shown ONLY in AI/Hybrid modes,
 *   a dedicated `[Google Web]` / `[Yerel ML Kiti]` telemetry source strip, and independent
 *   background-only opacity with 100%-opaque outlined text.
 * - In IN_PLACE_BLUR mode: collapses to a compact clickable "X" close badge anchored at the edge of
 *   the active blur region (stays clickable even when ROI is locked) to return to the control bubble.
 */
@SuppressLint("ViewConstructor")
class SubtitleHudOverlayView(
    context: Context,
    private val onDragDelta: (dx: Int, dy: Int) -> Unit,
    private val onDragFinished: () -> Unit = {},
    private val onClassicResizeDelta: (dw: Int, dh: Int) -> Unit = { _, _ -> },
    private val onClassicResizeFinished: () -> Unit = {},
    private val onClassicDesiredHeightChanged: (desiredHeightPx: Int) -> Unit = {},
    private val onToggleDisplayMode: () -> Unit,
    private val onOpenEnginePicker: () -> Unit,
    private val onOpenModelPicker: () -> Unit = {},
    private val onTogglePause: () -> Unit,
    private val onToggleLockRoi: () -> Unit,
    private val onOpenHistory: () -> Unit = {},
    private val onCloseActiveMode: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = jakartaFont
        isFakeBoldText = true
    }

    // Classic box container & children
    private val classicWrapper: FrameLayout
    private val classicContainer: LinearLayout
    private val classicBgDrawable: GradientDrawable
    private val headerRow: LinearLayout
    private val sourceStripRow: LinearLayout
    private val statusDot: View
    private val classicEngineChip: TextView
    private val classicModelChip: TextView
    private val classicModeChip: TextView
    private val sourceOriginBadge: TextView
    private val statusLabel: TextView
    private val classicHistoryButton: ImageButton
    private val classicPauseButton: ImageButton
    private val classicLockButton: ImageButton
    private val classicStopButton: ImageButton
    private val classicStealthButton: ImageButton
    private val textScrollView: ScrollView
    private val translatedTextView: OutlinedSubtitleTextView
    private val originalTextView: OutlinedSubtitleTextView
    private val classicResizeGrip: ClassicResizeGripView

    // Active In-Place Blur minimal inner top-right controls (Lock + Close) & optional error banner
    private val inPlaceHandleContainer: LinearLayout
    private val inPlaceControlsRow: LinearLayout
    private val inPlaceLockTouchWrapper: FrameLayout
    private val inPlaceLockButton: ImageView
    private val inPlaceLockBg: GradientDrawable
    private val inPlaceCloseTouchWrapper: FrameLayout
    private val inPlaceCloseButton: ImageView
    private val inPlaceCloseBg: GradientDrawable
    private val inPlaceErrorBanner: TextView

    private var isClassicBoxStealth: Boolean = false
    private var isInPlaceLocked: Boolean = false
    private var lastClassicBoxOpacity: Float = 0.86f
    private var lastBorderAccentColor: Int = Color.parseColor(TranslationEngineMode.OFFLINE_LOCAL.accentHex)
    private var currentDisplayMode: DisplayMode = DisplayMode.CLASSIC_BOX
    private var lastDesiredExpandedHeightPx: Int = -1
    private var lastHasError: Boolean = false

    init {
        val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

        // 1. Classic Box Layout
        classicWrapper = FrameLayout(context)

        classicBgDrawable = GradientDrawable().apply {
            setColor(Color.argb(220, 9, 14, 23))
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1.2f), Color.argb(120, 34, 211, 238))
        }

        classicContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(8f), dp(8f))
            background = classicBgDrawable
        }

        // Top Bar: Prominent Engine Pill + Conditional AI Model Pill + Display Mode + Utility Icons
        headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        classicEngineChip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = monoFont
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            contentDescription = "Aktif çeviri motorunu değiştir"
            setOnClickListener { onOpenEnginePicker() }
        }
        headerRow.addView(
            classicEngineChip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
                marginEnd = dp(4f)
            }
        )

        classicModelChip = createToggleChip(monoFont) { onOpenModelPicker() }.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(76f)
            contentDescription = "AI modelini seç"
            visibility = View.GONE
        }
        headerRow.addView(
            classicModelChip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
                marginEnd = dp(4f)
            }
        )

        classicModeChip = createToggleChip(monoFont) { onToggleDisplayMode() }
        headerRow.addView(
            classicModeChip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
                marginEnd = dp(2f)
            }
        )

        headerRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        classicHistoryButton = createHudButton(R.drawable.ic_hud_history, "Diyalog geçmişini aç", sizeDp = 28f) {
            onOpenHistory()
        }
        classicPauseButton = createHudButton(R.drawable.ic_hud_pause, "Duraklat veya devam et", sizeDp = 28f) {
            onTogglePause()
        }
        classicLockButton = createHudButton(R.drawable.ic_hud_unlock, "Bölgeyi kilitle veya düzenle", sizeDp = 28f) {
            onToggleLockRoi()
        }
        classicStopButton = createHudButton(
            R.drawable.ic_hud_close,
            "Aktif çeviri modunu kapat ve balona dön",
            sizeDp = 28f
        ) {
            onCloseActiveMode()
        }
        classicStealthButton = createHudButton(
            R.drawable.ic_hud_visibility,
            "Kutu arayüzünü gizle veya göster",
            sizeDp = 30f
        ) {
            isClassicBoxStealth = !isClassicBoxStealth
            applyClassicStealthVisibility()
        }

        headerRow.addView(classicHistoryButton)
        headerRow.addView(classicPauseButton)
        headerRow.addView(classicLockButton)
        headerRow.addView(classicStopButton)
        headerRow.addView(classicStealthButton)

        classicContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // Dedicated Telemetry & True Engine Origin Strip: "[Google Web] • 142ms"
        sourceStripRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(3f), dp(2f), dp(2f))
        }

        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22D3EE"))
            }
        }
        sourceStripRow.addView(statusDot, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply {
            marginEnd = dp(6f)
        })

        sourceOriginBadge = TextView(context).apply {
            text = "[Yerel ML Kiti]"
            setTextColor(Color.parseColor("#22D3EE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = monoFont
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        sourceStripRow.addView(
            sourceOriginBadge,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            }
        )

        statusLabel = TextView(context).apply {
            text = "HAZIR"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = monoFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        sourceStripRow.addView(statusLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        classicContainer.addView(
            sourceStripRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        textScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, dp(2f), 0, 0)
        }

        val textStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        translatedTextView = OutlinedSubtitleTextView(context, outlineWidthDp = 3.2f).apply {
            text = context.getString(R.string.overlay_waiting_subtitle)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = jakartaFont
            isFakeBoldTextCompat = true
            setPadding(dp(2f), 0, dp(2f), 0)
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            setLineSpacing(dp(2.5f).toFloat(), 1.0f)
            alpha = 1f
        }
        textStack.addView(
            translatedTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        originalTextView = OutlinedSubtitleTextView(context, outlineWidthDp = 2.4f).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = jakartaFont
            setPadding(dp(2f), 0, dp(2f), 0)
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            alpha = 1f
        }
        textStack.addView(
            originalTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            }
        )

        textScrollView.addView(
            textStack,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        classicContainer.addView(
            textScrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        classicWrapper.addView(
            classicContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        classicResizeGrip = ClassicResizeGripView(context).apply {
            contentDescription = "Altyazı kutusunu boyutlandır"
        }
        classicWrapper.addView(
            classicResizeGrip,
            LayoutParams(dp(40f), dp(40f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        )

        setupDragListener(headerRow)
        setupDragListener(sourceStripRow)
        setupTextAreaDragOrScroll(textScrollView)
        setupClassicResizeListener(classicResizeGrip)

        addView(classicWrapper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. Active In-Place Blur minimal inner top-right controls: [Lock (20dp)] [Close X (20dp)]
        inPlaceHandleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }

        inPlaceControlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        inPlaceLockBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(145, 9, 14, 23))
            setStroke(dp(0.9f).coerceAtLeast(1), Color.argb(145, 34, 211, 238))
        }
        inPlaceLockButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_blur_unlock_thin)
            contentDescription = "Blur bölgesini kilitle veya kilidi aç"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#E2E8F0"))
            background = inPlaceLockBg
            setPadding(dp(4.5f), dp(4.5f), dp(4.5f), dp(4.5f))
            alpha = 0.34f
        }
        inPlaceLockTouchWrapper = FrameLayout(context).apply {
            contentDescription = "Blur bölgesini kilitle veya kilidi aç"
            addView(
                inPlaceLockButton,
                LayoutParams(dp(20f), dp(20f)).apply {
                    gravity = Gravity.CENTER
                }
            )
            setupMinimalCornerTouch(inPlaceLockButton) { onToggleLockRoi() }
        }
        inPlaceControlsRow.addView(
            inPlaceLockTouchWrapper,
            LinearLayout.LayoutParams(dp(24f), dp(24f))
        )

        inPlaceCloseBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(145, 9, 14, 23))
            setStroke(dp(0.9f).coerceAtLeast(1), Color.argb(160, 251, 113, 133))
        }
        inPlaceCloseButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_blur_close_thin)
            contentDescription = "Doğal Blur modunu kapat ve balona dön"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#FFE4E6"))
            background = inPlaceCloseBg
            setPadding(dp(4.5f), dp(4.5f), dp(4.5f), dp(4.5f))
            alpha = 0.34f
        }
        inPlaceCloseTouchWrapper = FrameLayout(context).apply {
            contentDescription = "Doğal Blur modunu kapat ve balona dön"
            addView(
                inPlaceCloseButton,
                LayoutParams(dp(20f), dp(20f)).apply {
                    gravity = Gravity.CENTER
                }
            )
            setupMinimalCornerTouch(inPlaceCloseButton) { onCloseActiveMode() }
        }
        inPlaceControlsRow.addView(
            inPlaceCloseTouchWrapper,
            LinearLayout.LayoutParams(dp(24f), dp(24f))
        )

        inPlaceHandleContainer.addView(
            inPlaceControlsRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        inPlaceErrorBanner = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#FECDD3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = jakartaFont
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 69, 10, 26))
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(1f), Color.parseColor("#FB7185"))
            }
        }
        inPlaceHandleContainer.addView(
            inPlaceErrorBanner,
            LinearLayout.LayoutParams(dp(216f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            }
        )

        addView(inPlaceHandleContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun hasInPlaceError(): Boolean = inPlaceErrorBanner.visibility == View.VISIBLE

    fun setInPlaceStealth(hidden: Boolean) {
        if (currentDisplayMode != DisplayMode.IN_PLACE_BLUR) return
        val targetVis = if (hidden) View.INVISIBLE else View.VISIBLE
        val targetAlpha = if (hidden) 0f else 1f
        if (inPlaceHandleContainer.visibility == targetVis && inPlaceHandleContainer.alpha == targetAlpha) return
        inPlaceHandleContainer.visibility = targetVis
        inPlaceHandleContainer.alpha = targetAlpha
        inPlaceHandleContainer.invalidate()
    }

    /**
     * Adjusts ONLY the Classic Box background & border alpha (0% to 100%).
     * Translated text and its protective outline/shadow always remain at 100% full visibility.
     */
    fun updateClassicBoxOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0f, 1f)
        lastClassicBoxOpacity = clamped
        if (isClassicBoxStealth) {
            classicBgDrawable.setColor(Color.TRANSPARENT)
            classicBgDrawable.setStroke(0, Color.TRANSPARENT)
        } else {
            val bgAlpha = (clamped * 255f).toInt().coerceIn(0, 255)
            val borderColor = if (lastHasError) Color.parseColor("#FB7185") else lastBorderAccentColor
            val borderAlpha = if (lastHasError) 230 else (clamped * 0.55f * 255f).toInt().coerceIn(0, 255)
            classicBgDrawable.setColor(Color.argb(bgAlpha, 9, 14, 23))
            classicBgDrawable.setStroke(
                dp(if (lastHasError) 1.5f else 1.2f),
                Color.argb(borderAlpha, Color.red(borderColor), Color.green(borderColor), Color.blue(borderColor))
            )
        }
        translatedTextView.alpha = 1f
        originalTextView.alpha = 1f
    }

    private fun applyClassicStealthVisibility(showAiModelChip: Boolean = classicModelChip.visibility == View.VISIBLE) {
        val stealth = isClassicBoxStealth
        val chromeVis = if (stealth) View.INVISIBLE else View.VISIBLE
        val interactive = !stealth

        sourceStripRow.visibility = if (stealth) View.GONE else View.VISIBLE

        classicEngineChip.visibility = chromeVis
        classicEngineChip.isClickable = interactive
        classicEngineChip.isEnabled = interactive

        classicModeChip.visibility = chromeVis
        classicModeChip.isClickable = interactive
        classicModeChip.isEnabled = interactive

        if (showAiModelChip) {
            classicModelChip.visibility = chromeVis
            classicModelChip.isClickable = interactive
            classicModelChip.isEnabled = interactive
        } else {
            classicModelChip.visibility = View.GONE
            classicModelChip.isClickable = false
            classicModelChip.isEnabled = false
        }

        listOf(classicHistoryButton, classicPauseButton, classicLockButton).forEach { btn ->
            btn.visibility = chromeVis
            btn.isClickable = interactive
            btn.isEnabled = interactive
        }
        classicStopButton.visibility = View.VISIBLE
        classicStopButton.isClickable = true
        classicStopButton.isEnabled = true

        classicResizeGrip.visibility = chromeVis
        classicResizeGrip.isEnabled = interactive

        if (stealth) {
            originalTextView.visibility = View.GONE
            classicStealthButton.setImageResource(R.drawable.ic_hud_visibility_off)
            classicStealthButton.setColorFilter(Color.parseColor("#E2E8F0"))
            classicStealthButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(115, 9, 14, 23))
                setStroke(dp(1f), Color.argb(120, 148, 163, 184))
            }
            classicStopButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(115, 9, 14, 23))
                setStroke(dp(1f), Color.argb(120, 251, 113, 133))
            }
        } else {
            classicStealthButton.setImageResource(R.drawable.ic_hud_visibility)
            classicStealthButton.setColorFilter(Color.parseColor("#E2E8F0"))
            classicStealthButton.setBackgroundColor(Color.TRANSPARENT)
            classicStopButton.setBackgroundColor(Color.TRANSPARENT)
        }
        updateClassicBoxOpacity(lastClassicBoxOpacity)
    }

    fun render(telemetry: TranslatorTelemetry, showOriginal: Boolean) {
        val selectedEngine = telemetry.engineMode
        val engineAccentHex = selectedEngine.accentHex
        val engineAccentColor = Color.parseColor(engineAccentHex)
        lastBorderAccentColor = engineAccentColor
        lastHasError = telemetry.errorMessage != null

        val dotColor = when {
            telemetry.status == ServiceStatus.PAUSED -> Color.parseColor("#64748B")
            telemetry.errorMessage != null -> Color.parseColor("#FB7185")
            telemetry.isStabilizing || telemetry.isAiRefining -> Color.parseColor("#F59E0B")
            else -> engineAccentColor
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)

        val isBlur = telemetry.displayMode == DisplayMode.IN_PLACE_BLUR
        if (isBlur != (currentDisplayMode == DisplayMode.IN_PLACE_BLUR) ||
            (isBlur && inPlaceHandleContainer.visibility != View.VISIBLE) ||
            (!isBlur && classicWrapper.visibility != View.VISIBLE)
        ) {
            currentDisplayMode = telemetry.displayMode
            if (isBlur) {
                classicWrapper.visibility = View.GONE
                inPlaceHandleContainer.visibility = View.VISIBLE
            } else {
                inPlaceHandleContainer.visibility = View.GONE
                classicWrapper.visibility = View.VISIBLE
            }
        }

        if (isBlur) {
            isInPlaceLocked = telemetry.isRoiLocked
            val restingAlpha = if (isInPlaceLocked) 0.24f else 0.34f
            inPlaceLockButton.setImageResource(
                if (isInPlaceLocked) R.drawable.ic_blur_lock_thin else R.drawable.ic_blur_unlock_thin
            )
            inPlaceLockButton.setColorFilter(
                if (isInPlaceLocked) Color.parseColor("#67E8F9") else Color.parseColor("#E2E8F0")
            )
            inPlaceLockBg.setStroke(
                dp(0.9f).coerceAtLeast(1),
                if (isInPlaceLocked) Color.argb(180, 34, 211, 238) else Color.argb(135, 148, 163, 184)
            )
            inPlaceCloseBg.setStroke(
                dp(0.9f).coerceAtLeast(1),
                if (telemetry.errorMessage != null) Color.parseColor("#FB7185") else Color.argb(150, 251, 113, 133)
            )
            if (!inPlaceLockTouchWrapper.isPressed) {
                inPlaceLockButton.alpha = restingAlpha
            }
            if (!inPlaceCloseTouchWrapper.isPressed) {
                inPlaceCloseButton.alpha = restingAlpha
            }
            if (telemetry.errorMessage != null) {
                inPlaceErrorBanner.visibility = View.VISIBLE
                inPlaceErrorBanner.text = "${selectedEngine.formatSourceTag(telemetry.aiPreset)} ${telemetry.errorMessage}"
            } else {
                inPlaceErrorBanner.visibility = View.GONE
            }
            return
        }

        // 1. Prominent Active Engine Pill (tap opens EnginePickerOverlayView)
        classicEngineChip.text = when (selectedEngine) {
            TranslationEngineMode.SMART_AI -> "AI • ${telemetry.aiPreset.shortLabelTr} ▾"
            else -> "${selectedEngine.badgeLabelTr} ▾"
        }
        styleEnginePill(classicEngineChip, engineAccentColor)

        // 2. AI Model Pill — ONLY shown when SMART_AI or HYBRID is active!
        val showAiModel = selectedEngine.usesAiModel()
        if (showAiModel) {
            classicModelChip.text = compactModelLabel(telemetry.aiModelName)
            styleChip(classicModelChip, highlighted = true, highlightColor = "#F59E0B")
        }

        // 3. Display Mode Pill
        classicModeChip.text = telemetry.displayMode.shortLabelTr
        styleChip(classicModeChip, highlighted = false, highlightColor = "#94A3B8")

        val pauseRes = if (telemetry.status == ServiceStatus.PAUSED) R.drawable.ic_hud_play else R.drawable.ic_hud_pause
        val lockRes = if (telemetry.isRoiLocked) R.drawable.ic_hud_lock else R.drawable.ic_hud_unlock
        classicPauseButton.setImageResource(pauseRes)
        classicLockButton.setImageResource(lockRes)

        applyClassicStealthVisibility(showAiModelChip = showAiModel)

        // 4. Dedicated Source Origin Badge + Latency Telemetry Strip
        val effectiveOriginEngine = telemetry.lastResultEngineMode ?: selectedEngine
        val originColor = if (telemetry.errorMessage != null) {
            Color.parseColor("#FB7185")
        } else {
            Color.parseColor(effectiveOriginEngine.accentHex)
        }
        sourceOriginBadge.text = effectiveOriginEngine.formatSourceTag(telemetry.aiPreset)
        sourceOriginBadge.setTextColor(originColor)

        statusLabel.text = when {
            telemetry.status == ServiceStatus.PAUSED -> "• DURAKLATILDI"
            telemetry.errorMessage != null -> "• HATA"
            telemetry.isStabilizing -> "• SABİTLENİYOR…"
            telemetry.isAiRefining -> "• AI DÜZELTİYOR…"
            telemetry.lastFromCache && telemetry.currentTranslatedText.isNotEmpty() ->
                "• ÖNBELLEK (${telemetry.lastLatencyMs}ms)"
            telemetry.currentTranslatedText.isNotEmpty() ->
                "• ${telemetry.lastLatencyMs}ms"
            else -> "• HAZIR"
        }

        val targetSp = telemetry.fontSizeSp.coerceIn(12f, 32f)
        translatedTextView.visibility = View.VISIBLE
        translatedTextView.setTextColor(
            if (telemetry.errorMessage != null) Color.parseColor("#FECDD3")
            else telemetry.subtitleTextColorArgb
        )

        val nextText = when {
            telemetry.errorMessage != null -> telemetry.errorMessage
            telemetry.currentTranslatedText.isNotBlank() -> telemetry.currentTranslatedText
            else -> context.getString(R.string.overlay_waiting_subtitle)
        }

        val boxW = if (classicWrapper.width > 0) {
            classicWrapper.width
        } else {
            dp(telemetry.classicBoxWidthDp.coerceIn(200, 440).toFloat())
        }
        val availTextW = (boxW - dp(22f)).coerceAtLeast(dp(120f))
        val headerH = if (headerRow.height > 0) headerRow.height else dp(28f)
        val stripH = if (!isClassicBoxStealth && sourceStripRow.height > 0) sourceStripRow.height else if (isClassicBoxStealth) 0 else dp(18f)
        val baseBoxH = dp(telemetry.classicBoxHeightDp.coerceIn(84, 340).toFloat())
        val verticalChromePx = headerH + stripH + dp(20f)
        val availTextH = (baseBoxH - verticalChromePx).coerceAtLeast(dp(24f))

        val showOrigRow = !isClassicBoxStealth && showOriginal && telemetry.currentSourceText.isNotBlank() && telemetry.errorMessage == null
        val origExtraH = if (showOrigRow) {
            val origSp = (targetSp - 3.5f).coerceAtLeast(11f)
            measurePaint.textSize = origSp * scaledDensity
            val origLayout = StaticLayout.Builder
                .obtain(telemetry.currentSourceText, 0, telemetry.currentSourceText.length, measurePaint, availTextW)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()
            origLayout.height + dp(6f)
        } else {
            0
        }

        val effectiveAvailForTranslatedH = (availTextH - origExtraH).coerceAtLeast(dp(20f))

        val resolvedSp = when (telemetry.classicTextFitMode) {
            ClassicTextFitMode.WRAP_AND_EXPAND -> targetSp
            ClassicTextFitMode.AUTO_FIT_SHRINK -> resolveAutoFitFontSp(
                text = nextText,
                targetSp = targetSp,
                availWidthPx = availTextW,
                availHeightPx = effectiveAvailForTranslatedH
            )
        }

        translatedTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolvedSp)
        if (translatedTextView.text.toString() != nextText) {
            translatedTextView.text = nextText
            textScrollView.scrollTo(0, 0)
        }

        if (showOrigRow) {
            originalTextView.visibility = View.VISIBLE
            originalTextView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                (resolvedSp - 3.5f).coerceAtLeast(11f)
            )
            originalTextView.text = telemetry.currentSourceText
        } else {
            originalTextView.visibility = View.GONE
        }

        val desiredBoxHeightPx = if (telemetry.classicTextFitMode == ClassicTextFitMode.WRAP_AND_EXPAND) {
            measurePaint.textSize = resolvedSp * scaledDensity
            val transLayout = StaticLayout.Builder
                .obtain(nextText, 0, nextText.length, measurePaint, availTextW)
                .setLineSpacing(dp(2.5f).toFloat(), 1.0f)
                .setIncludePad(false)
                .build()
            maxOf(baseBoxH, verticalChromePx + transLayout.height + origExtraH + dp(4f))
        } else {
            baseBoxH
        }
        if (desiredBoxHeightPx != lastDesiredExpandedHeightPx) {
            lastDesiredExpandedHeightPx = desiredBoxHeightPx
            onClassicDesiredHeightChanged(desiredBoxHeightPx)
        }
    }

    private fun styleEnginePill(chip: TextView, accent: Int) {
        chip.setTextColor(Color.parseColor("#061019"))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(9f).toFloat()
            setColor(accent)
        }
    }

    private fun resolveAutoFitFontSp(
        text: String,
        targetSp: Float,
        availWidthPx: Int,
        availHeightPx: Int
    ): Float {
        if (text.isBlank()) return targetSp
        val minSp = 11f
        if (targetSp <= minSp) return minSp

        if (!text.contains('\n')) {
            var sp = targetSp
            while (sp >= minSp) {
                measurePaint.textSize = sp * scaledDensity
                if (measurePaint.measureText(text) <= availWidthPx - dp(2f)) {
                    return sp
                }
                sp -= 0.5f
            }
        }

        var sp = targetSp
        while (sp > minSp) {
            measurePaint.textSize = sp * scaledDensity
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, measurePaint, availWidthPx)
                .setLineSpacing(dp(2.5f).toFloat(), 1.0f)
                .setIncludePad(false)
                .build()
            if (layout.height <= availHeightPx) {
                return sp
            }
            sp = (sp - 0.5f).coerceAtLeast(minSp)
        }
        return minSp
    }

    private fun compactModelLabel(modelId: String): String {
        val tail = modelId.substringAfterLast('/').removeSuffix(":free")
        return when {
            tail.equals("gemini-2.5-flash-lite", ignoreCase = true) -> "2.5-lite"
            tail.equals("gemini-2.5-flash", ignoreCase = true) -> "2.5-flash"
            tail.length > 11 -> tail.take(10) + "…"
            else -> tail.ifBlank { "Model" }
        }
    }

    private fun createToggleChip(font: android.graphics.Typeface?, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = font
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            setOnClickListener { onClick() }
        }
    }

    private fun styleChip(chip: TextView, highlighted: Boolean, highlightColor: String) {
        val accent = Color.parseColor(highlightColor)
        chip.setTextColor(if (highlighted) accent else Color.parseColor("#CBD5E1"))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(
                if (highlighted) Color.argb(65, Color.red(accent), Color.green(accent), Color.blue(accent))
                else Color.argb(120, 15, 23, 42)
            )
            setStroke(dp(1f), if (highlighted) accent else Color.parseColor("#64748B"))
        }
    }

    private fun createHudButton(
        iconRes: Int,
        contentDesc: String,
        sizeDp: Float = 32f,
        onClick: () -> Unit
    ): ImageButton {
        val size = dp(sizeDp)
        return ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(4.5f), dp(4.5f), dp(4.5f), dp(4.5f))
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMinimalCornerTouch(visualBtn: View, onClick: () -> Unit) {
        val slop = dp(8f).toFloat()
        visualBtn.setOnTouchListener(null)
        // Attach to wrapper via parent when added
        (visualBtn.parent as? View)?.setOnTouchListener { wrapper, event ->
            val restingAlpha = if (isInPlaceLocked) 0.24f else 0.34f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wrapper.isPressed = true
                    visualBtn.animate().cancel()
                    visualBtn.alpha = 0.96f
                    visualBtn.scaleX = 1.08f
                    visualBtn.scaleY = 1.08f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x >= -slop && event.x <= wrapper.width + slop &&
                        event.y >= -slop && event.y <= wrapper.height + slop
                    visualBtn.alpha = if (inside) 0.96f else restingAlpha
                    visualBtn.scaleX = if (inside) 1.08f else 1f
                    visualBtn.scaleY = if (inside) 1.08f else 1f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    wrapper.isPressed = false
                    val inside = event.x >= -slop && event.x <= wrapper.width + slop &&
                        event.y >= -slop && event.y <= wrapper.height + slop
                    visualBtn.alpha = restingAlpha
                    visualBtn.scaleX = 1f
                    visualBtn.scaleY = 1f
                    if (inside) {
                        onClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    wrapper.isPressed = false
                    visualBtn.alpha = restingAlpha
                    visualBtn.scaleX = 1f
                    visualBtn.scaleY = 1f
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(target: View) {
        var lastRawX = 0f
        var lastRawY = 0f
        var moved = false
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    if (dx != 0 || dy != 0) {
                        moved = true
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onDragDelta(dx, dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) onDragFinished()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTextAreaDragOrScroll(scroll: ScrollView) {
        var lastRawX = 0f
        var lastRawY = 0f
        var moved = false
        scroll.setOnTouchListener { _, event ->
            val canScroll = scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)
            if (canScroll) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    if (dx != 0 || dy != 0) {
                        moved = true
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onDragDelta(dx, dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) onDragFinished()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupClassicResizeListener(target: View) {
        var lastRawX = 0f
        var lastRawY = 0f
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dw = (event.rawX - lastRawX).toInt()
                    val dh = (event.rawY - lastRawY).toInt()
                    if (dw != 0 || dh != 0) {
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onClassicResizeDelta(dw, dh)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onClassicResizeFinished()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private class ClassicResizeGripView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B322D3EE")
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val right = width - 8f * density
            val bottom = height - 8f * density
            canvas.drawLine(right - 10f * density, bottom, right, bottom - 10f * density, linePaint)
            canvas.drawLine(right - 5f * density, bottom, right, bottom - 5f * density, linePaint)
        }
    }

    /**
     * TextView that always renders a crisp thin dark outline + drop shadow under 100%-opaque text,
     * keeping subtitles legible even when Classic Box background opacity is reduced to 0%.
     */
    private class OutlinedSubtitleTextView(
        context: Context,
        outlineWidthDp: Float = 3.2f
    ) : TextView(context) {
        private val density = resources.displayMetrics.density
        private val outlineWidthPx = outlineWidthDp * density
        private val shadowRadiusPx = 4.5f * density
        private val shadowDyPx = 1.5f * density
        private val outlineColor = Color.argb(245, 4, 8, 15)
        private val shadowColor = Color.argb(245, 0, 0, 0)

        var isFakeBoldTextCompat: Boolean = false
            set(value) {
                field = value
                paint.isFakeBoldText = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val textLayout = layout
            if (textLayout == null) {
                super.onDraw(canvas)
                return
            }

            val p = paint
            val savedStyle = p.style
            val savedColor = p.color
            val savedStrokeWidth = p.strokeWidth
            val savedJoin = p.strokeJoin
            val savedCap = p.strokeCap
            val savedFakeBold = p.isFakeBoldText

            canvas.save()
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())

            p.isFakeBoldText = isFakeBoldTextCompat
            p.style = Paint.Style.STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = outlineWidthPx
            p.color = outlineColor
            p.setShadowLayer(shadowRadiusPx, 0f, shadowDyPx, shadowColor)
            textLayout.draw(canvas)

            p.style = Paint.Style.FILL
            p.clearShadowLayer()
            p.color = currentTextColor
            textLayout.draw(canvas)

            canvas.restore()

            p.style = savedStyle
            p.color = savedColor
            p.strokeWidth = savedStrokeWidth
            p.strokeJoin = savedJoin
            p.strokeCap = savedCap
            p.isFakeBoldText = savedFakeBold
        }
    }
}
