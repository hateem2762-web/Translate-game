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
import android.view.ViewConfiguration
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
import kotlin.math.abs

/**
 * Dual-mode floating HUD:
 * - In CLASSIC_BOX mode: renders a draggable & corner-resizable dark translucent subtitle box with
 *   independent background-only opacity (0%-100%), always-100%-opaque outlined/shadowed text,
 *   scrollable text area, and a quick Dialogue History button.
 * - In IN_PLACE_BLUR mode: hides the separate subtitle panel and collapses into a draggable
 *   circular floating corner button that expands a compact quick-control menu (including History).
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
    private val onToggleEngineMode: () -> Unit,
    private val onOpenModelPicker: () -> Unit = {},
    private val onTogglePause: () -> Unit,
    private val onToggleLockRoi: () -> Unit,
    private val onOpenHistory: () -> Unit = {},
    private val onOpenSettings: () -> Unit,
    private val onStopService: () -> Unit,
    private val onMenuExpansionChanged: (expanded: Boolean) -> Unit = {}
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = jakartaFont
        isFakeBoldText = true
    }

    // Classic box container & children
    private val classicWrapper: FrameLayout
    private val classicContainer: LinearLayout
    private val classicBgDrawable: GradientDrawable
    private val statusDot: View
    private val statusLabel: TextView
    private val classicModeChip: TextView
    private val classicEngineChip: TextView
    private val classicModelChip: TextView
    private val classicHistoryButton: ImageButton
    private val classicPauseButton: ImageButton
    private val classicLockButton: ImageButton
    private val classicStopButton: ImageButton
    private val classicStealthButton: ImageButton
    private val textScrollView: ScrollView
    private val translatedTextView: OutlinedSubtitleTextView
    private val originalTextView: OutlinedSubtitleTextView
    private val classicResizeGrip: ClassicResizeGripView

    // In-Place circular floating corner button & expandable quick menu
    private val cornerRoot: LinearLayout
    private val cornerMenuPill: LinearLayout
    private val cornerMenuBg: GradientDrawable
    private val cornerModeChip: TextView
    private val cornerEngineChip: TextView
    private val cornerModelChip: TextView
    private val cornerHistoryButton: ImageButton
    private val cornerPauseButton: ImageButton
    private val cornerLockButton: ImageButton
    private val cornerSettingsButton: ImageButton
    private val cornerStopButton: ImageButton
    private val cornerErrorBanner: TextView

    private val cornerFabContainer: FrameLayout
    private val cornerFabBg: GradientDrawable
    private val cornerFabIcon: ImageView
    private val cornerFabStatusDot: View

    private var isCornerMenuExpanded: Boolean = false
    private var isClassicBoxStealth: Boolean = false
    private var lastClassicBoxOpacity: Float = 0.86f
    private var currentDisplayMode: DisplayMode = DisplayMode.CLASSIC_BOX

    private var lastDesiredExpandedHeightPx: Int = -1
    private val headerRow: LinearLayout

    init {
        val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

        // 1. Classic Box Layout (resizable wrapper + scrollable outlined text + corner grip)
        classicWrapper = FrameLayout(context)

        classicBgDrawable = GradientDrawable().apply {
            setColor(Color.argb(220, 9, 14, 23))
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1f), Color.argb(107, 34, 211, 238))
        }

        classicContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(8f), dp(8f))
            background = classicBgDrawable
        }

        headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22D3EE"))
            }
        }
        headerRow.addView(statusDot, LinearLayout.LayoutParams(dp(8f), dp(8f)).apply {
            marginEnd = dp(6f)
        })

        statusLabel = TextView(context).apply {
            text = "HAZIR"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = monoFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        headerRow.addView(statusLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        classicModeChip = createToggleChip(monoFont) { onToggleDisplayMode() }
        classicEngineChip = createToggleChip(monoFont) { onToggleEngineMode() }
        classicModelChip = createToggleChip(monoFont) { onOpenModelPicker() }.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(78f)
            contentDescription = "AI modelini seç"
        }

        headerRow.addView(classicModeChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
            marginEnd = dp(3f)
        })
        headerRow.addView(classicEngineChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
            marginEnd = dp(3f)
        })
        headerRow.addView(classicModelChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
            marginEnd = dp(2f)
        })

        classicHistoryButton = createHudButton(R.drawable.ic_hud_history, "Diyalog geçmişini aç", sizeDp = 28f) {
            onOpenHistory()
        }
        classicPauseButton = createHudButton(R.drawable.ic_hud_pause, "Duraklat veya devam et", sizeDp = 28f) {
            onTogglePause()
        }
        classicLockButton = createHudButton(R.drawable.ic_hud_unlock, "Bölgeyi kilitle veya düzenle", sizeDp = 28f) {
            onToggleLockRoi()
        }
        classicStopButton = createHudButton(R.drawable.ic_hud_close, "Çeviriyi durdur", sizeDp = 28f) {
            onStopService()
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

        textScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, dp(3f), 0, 0)
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
        setupTextAreaDragOrScroll(textScrollView)
        setupClassicResizeListener(classicResizeGrip)

        addView(classicWrapper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. In-Place Floating Circular Corner Button + Expandable Menu
        cornerRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }

        val topCornerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        cornerMenuBg = GradientDrawable().apply {
            setColor(Color.argb(232, 9, 14, 23))
            cornerRadius = dp(24f).toFloat()
            setStroke(dp(1f), Color.parseColor("#6622D3EE"))
        }

        cornerMenuPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(6f), dp(4f))
            background = cornerMenuBg
            visibility = View.GONE
        }

        cornerModeChip = createToggleChip(monoFont) { onToggleDisplayMode() }
        cornerEngineChip = createToggleChip(monoFont) { onToggleEngineMode() }
        cornerModelChip = createToggleChip(monoFont) {
            collapseCornerMenu()
            onOpenModelPicker()
        }.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(82f)
            contentDescription = "AI modelini seç"
        }
        cornerHistoryButton = createHudButton(R.drawable.ic_hud_history, "Diyalog geçmişini aç") {
            collapseCornerMenu()
            onOpenHistory()
        }
        cornerPauseButton = createHudButton(R.drawable.ic_hud_pause, "Duraklat veya devam et") {
            onTogglePause()
        }
        cornerLockButton = createHudButton(R.drawable.ic_hud_unlock, "Bölgeyi kilitle veya düzenle") {
            onToggleLockRoi()
        }
        cornerSettingsButton = createHudButton(R.drawable.ic_hud_settings, "Ayarları aç") {
            collapseCornerMenu()
            onOpenSettings()
        }
        cornerStopButton = createHudButton(R.drawable.ic_hud_close, "Çeviriyi durdur") {
            onStopService()
        }

        cornerMenuPill.addView(cornerModeChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply {
            marginEnd = dp(3f)
        })
        cornerMenuPill.addView(cornerEngineChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply {
            marginEnd = dp(3f)
        })
        cornerMenuPill.addView(cornerModelChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply {
            marginEnd = dp(2f)
        })
        cornerMenuPill.addView(cornerHistoryButton)
        cornerMenuPill.addView(cornerPauseButton)
        cornerMenuPill.addView(cornerLockButton)
        cornerMenuPill.addView(cornerSettingsButton)
        cornerMenuPill.addView(cornerStopButton)

        topCornerRow.addView(
            cornerMenuPill,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            }
        )

        val fabSize = dp(48f)
        cornerFabBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(232, 9, 14, 23))
            setStroke(dp(1.5f), Color.parseColor("#B322D3EE"))
        }
        cornerFabContainer = FrameLayout(context).apply {
            background = cornerFabBg
            elevation = dp(4f).toFloat()
            contentDescription = "SubLens hızlı kontrol menüsü"
        }

        cornerFabIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_hud_blur)
            setColorFilter(Color.parseColor("#22D3EE"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
        }
        cornerFabContainer.addView(cornerFabIcon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        cornerFabStatusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
                setStroke(dp(1f), Color.parseColor("#090E17"))
            }
        }
        cornerFabContainer.addView(
            cornerFabStatusDot,
            LayoutParams(dp(10f), dp(10f)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(5f)
                marginEnd = dp(5f)
            }
        )

        setupCornerFabTouchListener(cornerFabContainer)

        topCornerRow.addView(cornerFabContainer, LinearLayout.LayoutParams(fabSize, fabSize))
        cornerRoot.addView(
            topCornerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        cornerErrorBanner = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#FECDD3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 69, 10, 26))
                cornerRadius = dp(12f).toFloat()
                setStroke(dp(1f), Color.parseColor("#FB7185"))
            }
        }
        cornerRoot.addView(
            cornerErrorBanner,
            LinearLayout.LayoutParams(dp(250f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            }
        )

        addView(cornerRoot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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
            val borderAlpha = (clamped * 0.42f * 255f).toInt().coerceIn(0, 255)
            classicBgDrawable.setColor(Color.argb(bgAlpha, 9, 14, 23))
            classicBgDrawable.setStroke(dp(1f), Color.argb(borderAlpha, 34, 211, 238))
        }
        translatedTextView.alpha = 1f
        originalTextView.alpha = 1f
    }

    private fun applyClassicStealthVisibility() {
        val stealth = isClassicBoxStealth
        val chromeVis = if (stealth) View.INVISIBLE else View.VISIBLE
        val interactive = !stealth

        statusDot.visibility = chromeVis
        statusLabel.visibility = chromeVis

        listOf(classicModeChip, classicEngineChip, classicModelChip).forEach { chip ->
            chip.visibility = chromeVis
            chip.isClickable = interactive
            chip.isEnabled = interactive
        }
        listOf(classicHistoryButton, classicPauseButton, classicLockButton, classicStopButton).forEach { btn ->
            btn.visibility = chromeVis
            btn.isClickable = interactive
            btn.isEnabled = interactive
        }
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
        } else {
            classicStealthButton.setImageResource(R.drawable.ic_hud_visibility)
            classicStealthButton.setColorFilter(Color.parseColor("#E2E8F0"))
            classicStealthButton.setBackgroundColor(Color.TRANSPARENT)
        }
        updateClassicBoxOpacity(lastClassicBoxOpacity)
    }

    fun isCornerMenuOpen(): Boolean = isCornerMenuExpanded

    private fun collapseCornerMenu() {
        isCornerMenuExpanded = false
        cornerMenuPill.visibility = View.GONE
        onMenuExpansionChanged(false)
    }

    fun render(telemetry: TranslatorTelemetry, showOriginal: Boolean) {
        val dotColor = when {
            telemetry.status == ServiceStatus.PAUSED -> Color.parseColor("#64748B")
            telemetry.errorMessage != null -> Color.parseColor("#FB7185")
            telemetry.isStabilizing || telemetry.isAiRefining -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#22D3EE")
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
        (cornerFabStatusDot.background as? GradientDrawable)?.setColor(
            if (telemetry.status == ServiceStatus.RUNNING &&
                telemetry.errorMessage == null &&
                !telemetry.isStabilizing &&
                !telemetry.isAiRefining
            ) {
                Color.parseColor("#10B981")
            } else {
                dotColor
            }
        )

        val isBlur = telemetry.displayMode == DisplayMode.IN_PLACE_BLUR
        val engineAccentHex = when (telemetry.engineMode) {
            TranslationEngineMode.HYBRID -> "#A78BFA"
            TranslationEngineMode.SMART_AI -> "#F59E0B"
            TranslationEngineMode.OFFLINE_LOCAL -> "#22D3EE"
        }
        val engineHighlighted = telemetry.engineMode != TranslationEngineMode.OFFLINE_LOCAL

        if (isBlur != (currentDisplayMode == DisplayMode.IN_PLACE_BLUR)) {
            currentDisplayMode = telemetry.displayMode
            if (isBlur) {
                classicWrapper.visibility = View.GONE
                cornerRoot.visibility = View.VISIBLE
                isCornerMenuExpanded = false
                cornerMenuPill.visibility = View.GONE
            } else {
                cornerRoot.visibility = View.GONE
                classicWrapper.visibility = View.VISIBLE
                isCornerMenuExpanded = false
                cornerMenuPill.visibility = View.GONE
            }
        }

        // Update chip & button states on both layouts
        classicModeChip.text = telemetry.displayMode.shortLabelTr
        cornerModeChip.text = telemetry.displayMode.shortLabelTr
        styleChip(classicModeChip, highlighted = isBlur, highlightColor = "#22D3EE")
        styleChip(cornerModeChip, highlighted = isBlur, highlightColor = "#22D3EE")

        classicEngineChip.text = telemetry.engineMode.shortLabelTr
        cornerEngineChip.text = telemetry.engineMode.shortLabelTr
        styleChip(classicEngineChip, highlighted = engineHighlighted, highlightColor = engineAccentHex)
        styleChip(cornerEngineChip, highlighted = engineHighlighted, highlightColor = engineAccentHex)

        val shortModel = compactModelLabel(telemetry.aiModelName)
        classicModelChip.text = shortModel
        cornerModelChip.text = shortModel
        styleChip(classicModelChip, highlighted = engineHighlighted, highlightColor = "#F59E0B")
        styleChip(cornerModelChip, highlighted = engineHighlighted, highlightColor = "#F59E0B")

        val pauseRes = if (telemetry.status == ServiceStatus.PAUSED) R.drawable.ic_hud_play else R.drawable.ic_hud_pause
        val lockRes = if (telemetry.isRoiLocked) R.drawable.ic_hud_lock else R.drawable.ic_hud_unlock
        classicPauseButton.setImageResource(pauseRes)
        cornerPauseButton.setImageResource(pauseRes)
        classicLockButton.setImageResource(lockRes)
        cornerLockButton.setImageResource(lockRes)

        if (isBlur) {
            cornerFabIcon.setColorFilter(Color.parseColor(engineAccentHex))
            cornerFabBg.setStroke(
                dp(1.5f),
                if (telemetry.errorMessage != null) Color.parseColor("#FB7185") else Color.parseColor(engineAccentHex)
            )
            if (isCornerMenuExpanded && telemetry.errorMessage != null) {
                cornerErrorBanner.visibility = View.VISIBLE
                cornerErrorBanner.text = telemetry.errorMessage
            } else {
                cornerErrorBanner.visibility = View.GONE
            }
        } else {
            applyClassicStealthVisibility()

            statusLabel.text = when {
                telemetry.status == ServiceStatus.PAUSED -> "DURAKLAT"
                telemetry.errorMessage != null -> "HATA"
                telemetry.isStabilizing -> "BEKLE…"
                telemetry.isAiRefining -> "AI DÜZELTİYOR…"
                telemetry.lastFromCache && telemetry.currentTranslatedText.isNotEmpty() ->
                    "CACHE ${telemetry.lastLatencyMs}ms"
                telemetry.currentTranslatedText.isNotEmpty() ->
                    "${telemetry.lastLatencyMs}ms"
                else -> "HAZIR"
            }

            val targetSp = telemetry.fontSizeSp.coerceIn(12f, 32f)
            translatedTextView.visibility = View.VISIBLE
            translatedTextView.setTextColor(telemetry.subtitleTextColorArgb)

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
            val headerH = if (headerRow.height > 0) headerRow.height else dp(32f)
            val baseBoxH = dp(telemetry.classicBoxHeightDp.coerceIn(84, 340).toFloat())
            val verticalChromePx = headerH + dp(20f)
            val availTextH = (baseBoxH - verticalChromePx).coerceAtLeast(dp(24f))

            val showOrigRow = !isClassicBoxStealth && showOriginal && telemetry.currentSourceText.isNotBlank()
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

        // 1. Prefer keeping text on 1 clean line without breaking awkwardly if achievable >= 11sp
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

        // 2. For multi-line / long dialogue that exceeds 1 line even at 11sp, fit cleanly inside availHeightPx
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
            tail.length > 13 -> tail.take(12) + "…"
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
    private fun setupCornerFabTouchListener(target: View) {
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var isDragging = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = abs(event.rawX - downRawX)
                    val totalDy = abs(event.rawY - downRawY)
                    if (totalDx > touchSlop || totalDy > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        if (dx != 0 || dy != 0) {
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                            onDragDelta(dx, dy)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        isCornerMenuExpanded = !isCornerMenuExpanded
                        cornerMenuPill.visibility = if (isCornerMenuExpanded) View.VISIBLE else View.GONE
                        onMenuExpansionChanged(isCornerMenuExpanded)
                    }
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
