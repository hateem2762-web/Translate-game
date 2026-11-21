package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.DisplayMode
import com.example.model.ServiceStatus
import com.example.model.TranslationEngineMode
import com.example.model.TranslatorTelemetry
import kotlin.math.abs

/**
 * Dual-mode floating HUD:
 * - In CLASSIC_BOX mode: renders the sleek dark translucent subtitle box + top control bar.
 * - In IN_PLACE_BLUR mode: completely hides the separate subtitle panel and collapses into a
 *   draggable circular floating corner button that expands a compact quick-control menu on tap.
 */
@SuppressLint("ViewConstructor")
class SubtitleHudOverlayView(
    context: Context,
    private val onDragDelta: (dx: Int, dy: Int) -> Unit,
    private val onToggleDisplayMode: () -> Unit,
    private val onToggleEngineMode: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onToggleLockRoi: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onStopService: () -> Unit,
    private val onMenuExpansionChanged: (expanded: Boolean) -> Unit = {}
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Classic box container & children
    private val classicContainer: LinearLayout
    private val classicBgDrawable: GradientDrawable
    private val statusDot: View
    private val statusLabel: TextView
    private val classicModeChip: TextView
    private val classicEngineChip: TextView
    private val classicPauseButton: ImageButton
    private val classicLockButton: ImageButton
    private val classicStopButton: ImageButton
    private val translatedTextView: TextView
    private val originalTextView: TextView

    // In-Place circular floating corner button & expandable quick menu
    private val cornerRoot: LinearLayout
    private val cornerMenuPill: LinearLayout
    private val cornerMenuBg: GradientDrawable
    private val cornerModeChip: TextView
    private val cornerEngineChip: TextView
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
    private var currentDisplayMode: DisplayMode = DisplayMode.CLASSIC_BOX

    init {
        val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
        val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

        // 1. Classic Box Layout
        classicBgDrawable = GradientDrawable().apply {
            setColor(Color.argb(220, 9, 14, 23))
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1f), Color.parseColor("#4D22D3EE"))
        }

        classicContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(8f), dp(10f))
            background = classicBgDrawable
        }

        val headerRow = LinearLayout(context).apply {
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
        }
        headerRow.addView(statusLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        classicModeChip = createToggleChip(monoFont) { onToggleDisplayMode() }
        classicEngineChip = createToggleChip(monoFont) { onToggleEngineMode() }

        headerRow.addView(classicModeChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28f)).apply {
            marginEnd = dp(4f)
        })
        headerRow.addView(classicEngineChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28f)).apply {
            marginEnd = dp(2f)
        })

        classicPauseButton = createHudButton(R.drawable.ic_hud_pause, "Duraklat veya devam et") {
            onTogglePause()
        }
        classicLockButton = createHudButton(R.drawable.ic_hud_unlock, "Bölgeyi kilitle veya düzenle") {
            onToggleLockRoi()
        }
        classicStopButton = createHudButton(R.drawable.ic_hud_close, "Çeviriyi durdur") {
            onStopService()
        }

        headerRow.addView(classicPauseButton)
        headerRow.addView(classicLockButton)
        headerRow.addView(classicStopButton)

        classicContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        translatedTextView = TextView(context).apply {
            text = context.getString(R.string.overlay_waiting_subtitle)
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = jakartaFont
            setLineSpacing(dp(3f).toFloat(), 1.0f)
            setShadowLayer(4f, 0f, 1.5f, Color.BLACK)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
        }
        classicContainer.addView(
            translatedTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            }
        )

        originalTextView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = jakartaFont
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        classicContainer.addView(
            originalTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            }
        )

        setupDragListener(headerRow)
        setupDragListener(translatedTextView)

        addView(classicContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        cornerPauseButton = createHudButton(R.drawable.ic_hud_pause, "Duraklat veya devam et") {
            onTogglePause()
        }
        cornerLockButton = createHudButton(R.drawable.ic_hud_unlock, "Bölgeyi kilitle veya düzenle") {
            onToggleLockRoi()
        }
        cornerSettingsButton = createHudButton(R.drawable.ic_hud_settings, "Ayarları aç") {
            isCornerMenuExpanded = false
            cornerMenuPill.visibility = View.GONE
            onMenuExpansionChanged(false)
            onOpenSettings()
        }
        cornerStopButton = createHudButton(R.drawable.ic_hud_close, "Çeviriyi durdur") {
            onStopService()
        }

        cornerMenuPill.addView(cornerModeChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply {
            marginEnd = dp(4f)
        })
        cornerMenuPill.addView(cornerEngineChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30f)).apply {
            marginEnd = dp(2f)
        })
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

    fun updateOpacity(opacity: Float) {
        val alpha = (opacity.coerceIn(0.45f, 0.96f) * 255).toInt()
        classicBgDrawable.setColor(Color.argb(alpha, 9, 14, 23))
    }

    fun isCornerMenuOpen(): Boolean = isCornerMenuExpanded

    fun render(telemetry: TranslatorTelemetry, showOriginal: Boolean) {
        val dotColor = when {
            telemetry.status == ServiceStatus.PAUSED -> Color.parseColor("#64748B")
            telemetry.errorMessage != null -> Color.parseColor("#FB7185")
            telemetry.isStabilizing -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#22D3EE")
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
        (cornerFabStatusDot.background as? GradientDrawable)?.setColor(
            if (telemetry.status == ServiceStatus.RUNNING && telemetry.errorMessage == null && !telemetry.isStabilizing) {
                Color.parseColor("#10B981")
            } else {
                dotColor
            }
        )

        val isBlur = telemetry.displayMode == DisplayMode.IN_PLACE_BLUR
        val isAi = telemetry.engineMode == TranslationEngineMode.SMART_AI

        if (isBlur != (currentDisplayMode == DisplayMode.IN_PLACE_BLUR)) {
            currentDisplayMode = telemetry.displayMode
            if (isBlur) {
                classicContainer.visibility = View.GONE
                cornerRoot.visibility = View.VISIBLE
                isCornerMenuExpanded = false
                cornerMenuPill.visibility = View.GONE
            } else {
                cornerRoot.visibility = View.GONE
                classicContainer.visibility = View.VISIBLE
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
        styleChip(classicEngineChip, highlighted = isAi, highlightColor = "#F59E0B")
        styleChip(cornerEngineChip, highlighted = isAi, highlightColor = "#F59E0B")

        val pauseRes = if (telemetry.status == ServiceStatus.PAUSED) R.drawable.ic_hud_play else R.drawable.ic_hud_pause
        val lockRes = if (telemetry.isRoiLocked) R.drawable.ic_hud_lock else R.drawable.ic_hud_unlock
        classicPauseButton.setImageResource(pauseRes)
        cornerPauseButton.setImageResource(pauseRes)
        classicLockButton.setImageResource(lockRes)
        cornerLockButton.setImageResource(lockRes)

        if (isBlur) {
            // In-Place Blur mode: NO separate subtitle panel at all.
            // Only the corner FAB (+ optional quick menu / error tooltip if tapped).
            val fabTint = if (isAi) "#F59E0B" else "#22D3EE"
            cornerFabIcon.setColorFilter(Color.parseColor(fabTint))
            cornerFabBg.setStroke(
                dp(1.5f),
                if (telemetry.errorMessage != null) Color.parseColor("#FB7185") else Color.parseColor(fabTint)
            )
            if (isCornerMenuExpanded && telemetry.errorMessage != null) {
                cornerErrorBanner.visibility = View.VISIBLE
                cornerErrorBanner.text = telemetry.errorMessage
            } else {
                cornerErrorBanner.visibility = View.GONE
            }
        } else {
            statusLabel.text = when {
                telemetry.status == ServiceStatus.PAUSED -> "DURAKLAT"
                telemetry.errorMessage != null -> "HATA"
                telemetry.isStabilizing -> "BEKLE…"
                telemetry.lastFromCache && telemetry.currentTranslatedText.isNotEmpty() ->
                    "CACHE ${telemetry.lastLatencyMs}ms"
                telemetry.currentTranslatedText.isNotEmpty() ->
                    "${telemetry.lastLatencyMs}ms"
                else -> "HAZIR"
            }

            translatedTextView.visibility = View.VISIBLE
            translatedTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, telemetry.fontSize.classicSp)
            translatedTextView.text = when {
                telemetry.errorMessage != null -> telemetry.errorMessage
                telemetry.currentTranslatedText.isNotBlank() -> telemetry.currentTranslatedText
                else -> context.getString(R.string.overlay_waiting_subtitle)
            }

            if (showOriginal && telemetry.currentSourceText.isNotBlank()) {
                originalTextView.visibility = View.VISIBLE
                originalTextView.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    (telemetry.fontSize.classicSp - 3.5f).coerceAtLeast(11f)
                )
                originalTextView.text = telemetry.currentSourceText
            } else {
                originalTextView.visibility = View.GONE
            }
        }
    }

    private fun createToggleChip(font: android.graphics.Typeface?, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = font
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            setOnClickListener { onClick() }
        }
    }

    private fun styleChip(chip: TextView, highlighted: Boolean, highlightColor: String) {
        val accent = Color.parseColor(highlightColor)
        chip.setTextColor(if (highlighted) accent else Color.parseColor("#CBD5E1"))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(
                if (highlighted) Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))
                else Color.argb(60, 31, 45, 69)
            )
            setStroke(dp(1f), if (highlighted) accent else Color.parseColor("#475569"))
        }
    }

    private fun createHudButton(iconRes: Int, contentDesc: String, onClick: () -> Unit): ImageButton {
        val size = dp(34f)
        return ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
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
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    if (dx != 0 || dy != 0) {
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        onDragDelta(dx, dy)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()
}
