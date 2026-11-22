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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.DisplayMode
import com.example.model.FloatingBubbleBehavior
import com.example.model.FloatingBubbleTheme
import com.example.model.OverlayHubStage
import com.example.model.ServiceStatus
import com.example.model.TranslationEngineMode
import com.example.model.TranslatorTelemetry
import kotlin.math.abs

/**
 * Floating control hub for SubLens:
 * - BUBBLE_IDLE: shows a draggable circular themed control bubble; tapping opens the mode selector menu.
 * - ACTIVE_MODE + HIDE_WHEN_ACTIVE: smoothly hides while active mode ("Klasik Kutu" / "Doğal Blur") runs.
 * - ACTIVE_MODE + DOCK_HALF_CIRCLE: docks at the bottom-right screen edge as a themed half-circle "<" tab;
 *   tapping "<" pops open the control & mode switcher menu cleanly.
 */
@SuppressLint("ViewConstructor")
class FloatingControlBubbleOverlayView(
    context: Context,
    private val onDragBubbleDelta: (dx: Int, dy: Int) -> Unit,
    private val onDragBubbleFinished: () -> Unit,
    private val onDragDockVerticalDelta: (dy: Int) -> Unit,
    private val onDragDockFinished: () -> Unit,
    private val onSelectTranslationMode: (DisplayMode) -> Unit,
    private val onCloseActiveMode: () -> Unit,
    private val onToggleEngineMode: () -> Unit,
    private val onOpenModelPicker: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onToggleLockRoi: () -> Unit,
    private val onOpenHistory: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onStopService: () -> Unit,
    private val onLayoutRequirementChanged: () -> Unit
) : FrameLayout(context) {

    enum class VisualPresentation {
        BUBBLE_ONLY,
        MENU_WITH_BUBBLE,
        HALF_CIRCLE_DOCK,
        HIDDEN
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
    private val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

    private val rootRow: LinearLayout
    private val menuCard: LinearLayout
    private val menuBgDrawable: GradientDrawable

    private val menuHeaderTitle: TextView
    private val menuCloseBtn: ImageButton

    private val classicModeBtn: LinearLayout
    private val classicModeTitle: TextView
    private val classicModeSub: TextView
    private val classicModeBadge: TextView

    private val blurModeBtn: LinearLayout
    private val blurModeTitle: TextView
    private val blurModeSub: TextView
    private val blurModeBadge: TextView

    private val engineChip: TextView
    private val modelChip: TextView

    private val historyBtn: ImageButton
    private val lockBtn: ImageButton
    private val pauseBtn: ImageButton
    private val settingsBtn: ImageButton

    private val closeActiveModeBtn: TextView
    private val stopAllBtn: TextView

    private val bubbleView: ThemedBubbleButtonView
    private val dockView: ThemedHalfCircleDockView

    private var isMenuExpanded: Boolean = false
    private var currentStage: OverlayHubStage = OverlayHubStage.BUBBLE_IDLE
    private var currentBehavior: FloatingBubbleBehavior = FloatingBubbleBehavior.HIDE_WHEN_ACTIVE
    private var currentTheme: FloatingBubbleTheme = FloatingBubbleTheme.NEON
    private var currentPresentation: VisualPresentation = VisualPresentation.BUBBLE_ONLY

    init {
        clipChildren = false
        clipToPadding = false

        rootRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.END
            clipChildren = false
            clipToPadding = false
        }

        val spec = bubbleThemeSpec(currentTheme)
        menuBgDrawable = GradientDrawable().apply {
            setColor(spec.menuBgColor)
            cornerRadius = dp(18f).toFloat()
            setStroke(dp(1.2f), spec.menuBorderColor)
        }

        menuCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = menuBgDrawable
            elevation = dp(8f).toFloat()
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }

        // Header Row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        menuHeaderTitle = TextView(context).apply {
            text = context.getString(R.string.bubble_menu_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        menuCloseBtn = createActionIcon(R.drawable.ic_hud_close, "Menüyü kapat", sizeDp = 28f) {
            collapseMenu()
        }
        header.addView(menuHeaderTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(menuCloseBtn)
        menuCard.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Mode Picker Section
        val modesLabel = TextView(context).apply {
            text = "ÇEVİRİ MODUNU SEÇ"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = monoFont
        }
        menuCard.addView(
            modesLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
                bottomMargin = dp(5f)
            }
        )

        val classicPair = buildModeOptionRow(
            title = "Klasik Kutu",
            subtitle = "Ayrı ve boyutlanabilir altyazı kutusu",
            iconRes = R.drawable.ic_hud_visibility
        ) {
            collapseMenu(notifyLayout = false)
            onSelectTranslationMode(DisplayMode.CLASSIC_BOX)
        }
        classicModeBtn = classicPair.row
        classicModeTitle = classicPair.title
        classicModeSub = classicPair.subtitle
        classicModeBadge = classicPair.badge

        menuCard.addView(
            classicModeBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6f)
            }
        )

        val blurPair = buildModeOptionRow(
            title = "Doğal Blur",
            subtitle = "Orijinal yazının üzerine buzlu çeviri",
            iconRes = R.drawable.ic_hud_blur
        ) {
            collapseMenu(notifyLayout = false)
            onSelectTranslationMode(DisplayMode.IN_PLACE_BLUR)
        }
        blurModeBtn = blurPair.row
        blurModeTitle = blurPair.title
        blurModeSub = blurPair.subtitle
        blurModeBadge = blurPair.badge

        menuCard.addView(
            blurModeBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        )

        // Engine & Model Row
        val engineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        engineChip = createChipText(monoFont) { onToggleEngineMode() }
        modelChip = createChipText(monoFont) {
            collapseMenu()
            onOpenModelPicker()
        }.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        engineRow.addView(engineChip, LinearLayout.LayoutParams(0, dp(32f), 0.9f).apply {
            marginEnd = dp(6f)
        })
        engineRow.addView(modelChip, LinearLayout.LayoutParams(0, dp(32f), 1.1f))
        menuCard.addView(
            engineRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        )

        // Utility Actions Row
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(110, 15, 23, 42))
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        }
        historyBtn = createActionIcon(R.drawable.ic_hud_history, "Diyalog geçmişi") {
            collapseMenu()
            onOpenHistory()
        }
        lockBtn = createActionIcon(R.drawable.ic_hud_unlock, "Bölgeyi kilitle veya aç") {
            onToggleLockRoi()
        }
        pauseBtn = createActionIcon(R.drawable.ic_hud_pause, "Duraklat veya devam et") {
            onTogglePause()
        }
        settingsBtn = createActionIcon(R.drawable.ic_hud_settings, "Ayarları aç") {
            collapseMenu()
            onOpenSettings()
        }
        listOf(historyBtn, lockBtn, pauseBtn, settingsBtn).forEach { btn ->
            actionsRow.addView(btn, LinearLayout.LayoutParams(0, dp(36f), 1f))
        }
        menuCard.addView(
            actionsRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        )

        // Close Active Mode button (shown only while a mode is active)
        closeActiveModeBtn = TextView(context).apply {
            text = context.getString(R.string.bubble_close_active_mode)
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 30, 41, 59))
                cornerRadius = dp(11f).toFloat()
                setStroke(dp(1f), Color.argb(140, 148, 163, 184))
            }
            visibility = View.GONE
            setOnClickListener {
                collapseMenu(notifyLayout = false)
                onCloseActiveMode()
            }
        }
        menuCard.addView(
            closeActiveModeBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6f)
            }
        )

        // Stop Service Completely button
        stopAllBtn = TextView(context).apply {
            text = context.getString(R.string.bubble_stop_completely)
            setTextColor(Color.parseColor("#FECDD3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                setColor(Color.argb(195, 127, 29, 29))
                cornerRadius = dp(11f).toFloat()
                setStroke(dp(1f), Color.parseColor("#FB7185"))
            }
            setOnClickListener {
                collapseMenu(notifyLayout = false)
                onStopService()
            }
        }
        menuCard.addView(
            stopAllBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // Circular Draggable Bubble
        bubbleView = ThemedBubbleButtonView(context).apply {
            contentDescription = "SubLens kontrol balonu"
        }
        setupBubbleTouchListener(bubbleView)

        // Right-edge Half-Circle "<" Dock
        dockView = ThemedHalfCircleDockView(context).apply {
            contentDescription = "Kontrol menüsünü aç"
            visibility = View.GONE
        }
        setupDockTouchListener(dockView)

        rootRow.addView(
            menuCard,
            LinearLayout.LayoutParams(dp(236f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8f)
            }
        )
        rootRow.addView(bubbleView, LinearLayout.LayoutParams(dp(62f), dp(62f)))
        rootRow.addView(dockView, LinearLayout.LayoutParams(dp(34f), dp(60f)))

        addView(rootRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun isMenuOpen(): Boolean = isMenuExpanded

    fun currentPresentation(): VisualPresentation = currentPresentation

    fun collapseMenu(notifyLayout: Boolean = true) {
        if (!isMenuExpanded) return
        isMenuExpanded = false
        menuCard.animate().cancel()
        menuCard.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(140L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!isMenuExpanded) {
                    menuCard.visibility = View.GONE
                    refreshPresentation(animateTransition = true)
                    if (notifyLayout) onLayoutRequirementChanged()
                }
            }
            .start()
    }

    private fun expandMenu() {
        if (isMenuExpanded) return
        isMenuExpanded = true
        refreshPresentation(animateTransition = false)
        onLayoutRequirementChanged()
        menuCard.visibility = View.VISIBLE
        menuCard.pivotX = menuCard.width.toFloat().coerceAtLeast(dp(220f).toFloat())
        menuCard.pivotY = menuCard.height.toFloat().coerceAtLeast(dp(180f).toFloat())
        menuCard.alpha = 0f
        menuCard.scaleX = 0.86f
        menuCard.scaleY = 0.86f
        menuCard.animate().cancel()
        menuCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(210L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    fun render(telemetry: TranslatorTelemetry) {
        val prevStage = currentStage
        val prevBehavior = currentBehavior
        currentStage = telemetry.overlayHubStage
        currentBehavior = telemetry.bubbleBehavior
        currentTheme = telemetry.bubbleTheme

        val spec = bubbleThemeSpec(currentTheme)
        menuBgDrawable.setColor(spec.menuBgColor)
        menuBgDrawable.setStroke(dp(1.2f), spec.menuBorderColor)

        val dotColor = when {
            telemetry.status == ServiceStatus.PAUSED -> Color.parseColor("#64748B")
            telemetry.errorMessage != null -> Color.parseColor("#FB7185")
            telemetry.isStabilizing || telemetry.isAiRefining -> Color.parseColor("#F59E0B")
            currentStage == OverlayHubStage.ACTIVE_MODE -> Color.parseColor("#10B981")
            else -> spec.accentColor
        }

        bubbleView.theme = currentTheme
        bubbleView.statusDotColor = dotColor
        dockView.theme = currentTheme
        dockView.statusDotColor = dotColor

        // Mode options state
        val isActive = currentStage == OverlayHubStage.ACTIVE_MODE
        styleModeOption(
            row = classicModeBtn,
            title = classicModeTitle,
            sub = classicModeSub,
            badge = classicModeBadge,
            selected = isActive && telemetry.displayMode == DisplayMode.CLASSIC_BOX,
            spec = spec
        )
        styleModeOption(
            row = blurModeBtn,
            title = blurModeTitle,
            sub = blurModeSub,
            badge = blurModeBadge,
            selected = isActive && telemetry.displayMode == DisplayMode.IN_PLACE_BLUR,
            spec = spec
        )

        // Engine & model chips
        val engineAccent = when (telemetry.engineMode) {
            TranslationEngineMode.HYBRID -> Color.parseColor("#A78BFA")
            TranslationEngineMode.SMART_AI -> Color.parseColor("#F59E0B")
            TranslationEngineMode.OFFLINE_LOCAL -> spec.accentColor
        }
        engineChip.text = "Motor: ${telemetry.engineMode.shortLabelTr}"
        styleChip(engineChip, engineAccent, highlighted = telemetry.engineMode != TranslationEngineMode.OFFLINE_LOCAL)

        modelChip.text = compactModelLabel(telemetry.aiModelName)
        styleChip(modelChip, Color.parseColor("#F59E0B"), highlighted = telemetry.engineMode != TranslationEngineMode.OFFLINE_LOCAL)

        pauseBtn.setImageResource(if (telemetry.status == ServiceStatus.PAUSED) R.drawable.ic_hud_play else R.drawable.ic_hud_pause)
        lockBtn.setImageResource(if (telemetry.isRoiLocked) R.drawable.ic_hud_lock else R.drawable.ic_hud_unlock)

        closeActiveModeBtn.visibility = if (isActive) View.VISIBLE else View.GONE

        val stageOrBehaviorChanged = prevStage != currentStage || prevBehavior != currentBehavior
        refreshPresentation(animateTransition = stageOrBehaviorChanged)
    }

    private fun refreshPresentation(animateTransition: Boolean) {
        val next = when {
            isMenuExpanded -> VisualPresentation.MENU_WITH_BUBBLE
            currentStage == OverlayHubStage.BUBBLE_IDLE -> VisualPresentation.BUBBLE_ONLY
            currentBehavior == FloatingBubbleBehavior.DOCK_HALF_CIRCLE -> VisualPresentation.HALF_CIRCLE_DOCK
            else -> VisualPresentation.HIDDEN
        }
        if (next == currentPresentation && !animateTransition) return
        val prev = currentPresentation
        currentPresentation = next

        when (next) {
            VisualPresentation.BUBBLE_ONLY, VisualPresentation.MENU_WITH_BUBBLE -> {
                dockView.visibility = View.GONE
                bubbleView.visibility = View.VISIBLE
                visibility = View.VISIBLE
                if (prev == VisualPresentation.HIDDEN || prev == VisualPresentation.HALF_CIRCLE_DOCK) {
                    bubbleView.scaleX = 0.72f
                    bubbleView.scaleY = 0.72f
                    bubbleView.alpha = 0f
                    bubbleView.animate().cancel()
                    bubbleView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(220L)
                        .setInterpolator(OvershootInterpolator(1.25f))
                        .start()
                } else {
                    bubbleView.scaleX = 1f
                    bubbleView.scaleY = 1f
                    bubbleView.alpha = 1f
                }
            }

            VisualPresentation.HALF_CIRCLE_DOCK -> {
                menuCard.visibility = View.GONE
                bubbleView.visibility = View.GONE
                dockView.visibility = View.VISIBLE
                visibility = View.VISIBLE
                dockView.translationX = dp(24f).toFloat()
                dockView.alpha = 0f
                dockView.animate().cancel()
                dockView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            VisualPresentation.HIDDEN -> {
                menuCard.visibility = View.GONE
                dockView.visibility = View.GONE
                if (animateTransition && visibility == View.VISIBLE) {
                    bubbleView.animate().cancel()
                    bubbleView.animate()
                        .scaleX(0.45f)
                        .scaleY(0.45f)
                        .alpha(0f)
                        .setDuration(160L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (currentPresentation == VisualPresentation.HIDDEN) {
                                visibility = View.GONE
                                bubbleView.scaleX = 1f
                                bubbleView.scaleY = 1f
                                bubbleView.alpha = 1f
                            }
                        }
                        .start()
                } else {
                    visibility = View.GONE
                }
            }
        }
    }

    private data class ModeRowRefs(
        val row: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val badge: TextView
    )

    private fun buildModeOptionRow(
        title: String,
        subtitle: String,
        iconRes: Int,
        onClick: () -> Unit
    ): ModeRowRefs {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            minimumHeight = dp(48f)
            setOnClickListener { onClick() }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
            marginEnd = dp(9f)
        })

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleTv = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
        }
        val subTv = TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = jakartaFont
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(titleTv)
        col.addView(subTv)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val badgeTv = TextView(context).apply {
            text = "AKTİF"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = monoFont
            paint.isFakeBoldText = true
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            visibility = View.GONE
        }
        row.addView(badgeTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return ModeRowRefs(row, titleTv, subTv, badgeTv)
    }

    private fun styleModeOption(
        row: LinearLayout,
        title: TextView,
        sub: TextView,
        badge: TextView,
        selected: Boolean,
        spec: BubbleThemeSpec
    ) {
        row.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(if (selected) spec.chipHighlightBg else Color.argb(120, 15, 23, 42))
            setStroke(
                dp(if (selected) 1.3f else 1f),
                if (selected) spec.accentColor else Color.argb(90, 100, 116, 139)
            )
        }
        title.setTextColor(if (selected) spec.accentColor else Color.WHITE)
        sub.setTextColor(if (selected) Color.parseColor("#CBD5E1") else Color.parseColor("#94A3B8"))
        badge.visibility = if (selected) View.VISIBLE else View.GONE
        badge.setTextColor(spec.accentColor)
        badge.background = GradientDrawable().apply {
            cornerRadius = dp(6f).toFloat()
            setColor(Color.argb(65, Color.red(spec.accentColor), Color.green(spec.accentColor), Color.blue(spec.accentColor)))
        }
    }

    private fun createChipText(font: android.graphics.Typeface?, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = font
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { onClick() }
        }
    }

    private fun styleChip(chip: TextView, accent: Int, highlighted: Boolean) {
        chip.setTextColor(if (highlighted) accent else Color.parseColor("#CBD5E1"))
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(
                if (highlighted) Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent))
                else Color.argb(120, 15, 23, 42)
            )
            setStroke(dp(1f), if (highlighted) accent else Color.argb(110, 100, 116, 139))
        }
    }

    private fun createActionIcon(
        iconRes: Int,
        contentDesc: String,
        sizeDp: Float = 36f,
        onClick: () -> Unit
    ): ImageButton {
        val px = dp(sizeDp)
        return ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(px, px)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener(target: View) {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var isDragging = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    isDragging = false
                    target.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val dx = (event.rawX - lastX).toInt()
                        val dy = (event.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            lastX = event.rawX
                            lastY = event.rawY
                            onDragBubbleDelta(dx, dy)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(140L).setInterpolator(OvershootInterpolator()).start()
                    if (event.actionMasked == MotionEvent.ACTION_UP && !isDragging) {
                        if (isMenuExpanded) collapseMenu() else expandMenu()
                    } else if (isDragging) {
                        onDragBubbleFinished()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDockTouchListener(target: View) {
        var downX = 0f
        var downY = 0f
        var lastY = 0f
        var isDragging = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastY = event.rawY
                    isDragging = false
                    target.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val dy = (event.rawY - lastY).toInt()
                        if (dy != 0) {
                            lastY = event.rawY
                            onDragDockVerticalDelta(dy)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    if (event.actionMasked == MotionEvent.ACTION_UP && !isDragging) {
                        expandMenu()
                    } else if (isDragging) {
                        onDragDockFinished()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun compactModelLabel(modelId: String): String {
        val tail = modelId.substringAfterLast('/').removeSuffix(":free")
        return when {
            tail.equals("gemini-2.5-flash-lite", ignoreCase = true) -> "Model: 2.5-lite"
            tail.equals("gemini-2.5-flash", ignoreCase = true) -> "Model: 2.5-flash"
            tail.length > 12 -> "Model: ${tail.take(10)}…"
            else -> "Model: ${tail.ifBlank { "Seç" }}"
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()
}
