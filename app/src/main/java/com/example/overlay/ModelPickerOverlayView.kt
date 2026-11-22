package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.capture.ScreenMetricsHelper
import com.example.model.AiModelOption
import com.example.model.AiProviderPreset
import kotlin.math.roundToInt

/**
 * Floating AI Provider & Model Picker window shown directly over games when the user taps
 * the Model selector button inside the Classic Box or Blur Corner Menu.
 */
@SuppressLint("ViewConstructor")
class ModelPickerOverlayView(
    context: Context,
    private val onSelectPreset: (AiProviderPreset) -> Unit,
    private val onSelectModel: (AiProviderPreset, String) -> Unit,
    private val onRefreshModels: (AiProviderPreset) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
    private val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

    private val presetsRow: LinearLayout
    private val statusNoteView: TextView
    private val refreshButton: TextView
    private val modelsContainer: LinearLayout

    private var activePreset: AiProviderPreset = AiProviderPreset.GROQ
    private var activeModelId: String = AiProviderPreset.GROQ.defaultModel
    private var configuredPresets: Set<AiProviderPreset> = emptySet()
    private var modelsByPreset: Map<AiProviderPreset, List<AiModelOption>> = emptyMap()
    private var isRefreshing: Boolean = false
    private var statusMessage: String? = null

    init {
        setBackgroundColor(Color.argb(148, 3, 6, 12))
        setOnClickListener { onDismiss() }

        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val panelWidth = minOf(dp(368f), (bounds.width * 0.90f).roundToInt())
        val panelMaxHeight = minOf(dp(440f), (bounds.height * 0.68f).roundToInt())

        val cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            isClickable = true
            isFocusable = true
            elevation = dp(10f).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 9, 14, 23))
                cornerRadius = dp(18f).toFloat()
                setStroke(dp(1.2f), Color.parseColor("#88F59E0B"))
            }
        }

        // Header
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "AI Sağlayıcı ve Model Seç"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        refreshButton = TextView(context).apply {
            text = "Yenile"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.argb(42, 245, 158, 11))
                setStroke(dp(1f), Color.argb(135, 245, 158, 11))
            }
            setOnClickListener {
                if (!isRefreshing) {
                    onRefreshModels(activePreset)
                }
            }
        }
        headerRow.addView(
            refreshButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            }
        )

        val closeBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_hud_close)
            contentDescription = "Model seçiciyi kapat"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setOnClickListener { onDismiss() }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(34f), dp(34f)))

        cardContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // Horizontal preset strip
        val presetScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        presetsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetScroll.addView(
            presetsRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        cardContainer.addView(
            presetScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        )

        statusNoteView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = jakartaFont
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            visibility = View.GONE
        }
        cardContainer.addView(
            statusNoteView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            }
        )

        val divider = View(context).apply {
            setBackgroundColor(Color.argb(45, 148, 163, 184))
        }
        cardContainer.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
        )

        val modelsScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        modelsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        modelsScroll.addView(
            modelsContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        cardContainer.addView(
            modelsScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val footerHint = TextView(context).apply {
            text = "Seçim anında uygulanır • Kapatmak için dışarı dokunun"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = jakartaFont
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, 0)
        }
        cardContainer.addView(
            footerHint,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        addView(
            cardContainer,
            LayoutParams(panelWidth, panelMaxHeight).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    fun render(
        preset: AiProviderPreset,
        modelId: String,
        configured: Set<AiProviderPreset>,
        catalogByPreset: Map<AiProviderPreset, List<AiModelOption>>,
        refreshing: Boolean = isRefreshing,
        note: String? = statusMessage
    ) {
        activePreset = preset
        activeModelId = AiProviderPreset.sanitizeModelId(preset, modelId)
        configuredPresets = configured
        modelsByPreset = catalogByPreset
        isRefreshing = refreshing
        statusMessage = note

        refreshButton.text = if (refreshing) "Alınıyor…" else "Yenile"
        refreshButton.alpha = if (refreshing) 0.55f else 1f

        renderPresetChips()
        renderStatusBanner()
        renderModelRows()
    }

    private fun renderPresetChips() {
        presetsRow.removeAllViews()
        AiProviderPreset.entries.forEachIndexed { idx, preset ->
            val selected = preset == activePreset
            val hasKey = preset in configuredPresets || !preset.requiresUserApiKey
            val chip = TextView(context).apply {
                text = if (hasKey) "● ${preset.shortLabelTr}" else preset.shortLabelTr
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = jakartaFont
                paint.isFakeBoldText = selected
                setTextColor(
                    when {
                        selected -> Color.parseColor("#F59E0B")
                        hasKey -> Color.parseColor("#E2E8F0")
                        else -> Color.parseColor("#94A3B8")
                    }
                )
                setPadding(dp(11f), dp(7f), dp(11f), dp(7f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(
                        if (selected) Color.argb(56, 245, 158, 11)
                        else Color.argb(145, 15, 23, 42)
                    )
                    setStroke(
                        dp(1f),
                        if (selected) Color.parseColor("#F59E0B")
                        else Color.parseColor("#475569")
                    )
                }
                setOnClickListener {
                    if (preset != activePreset) {
                        onSelectPreset(preset)
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) marginStart = dp(6f)
            }
            presetsRow.addView(chip, lp)
        }
    }

    private fun renderStatusBanner() {
        val customNote = statusMessage
        val needsKey = activePreset.requiresUserApiKey && activePreset !in configuredPresets
        when {
            !customNote.isNullOrBlank() -> {
                statusNoteView.visibility = View.VISIBLE
                statusNoteView.text = customNote
                statusNoteView.setTextColor(Color.parseColor("#E2E8F0"))
                statusNoteView.background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(Color.argb(42, 34, 211, 238))
                }
            }
            needsKey -> {
                statusNoteView.visibility = View.VISIBLE
                statusNoteView.text = "${activePreset.shortLabelTr} API anahtarı henüz girilmedi — Kontrol sekmesinden ekleyebilirsiniz."
                statusNoteView.setTextColor(Color.parseColor("#FDE68A"))
                statusNoteView.background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(Color.argb(40, 245, 158, 11))
                }
            }
            else -> {
                statusNoteView.visibility = View.GONE
            }
        }
    }

    private fun renderModelRows() {
        modelsContainer.removeAllViews()
        val baseList = modelsByPreset[activePreset] ?: activePreset.curatedModels
        val models = if (activeModelId.isNotBlank() && baseList.none { it.id == activeModelId }) {
            listOf(AiModelOption(activeModelId, activeModelId)) + baseList
        } else {
            baseList
        }

        models.forEachIndexed { idx, option ->
            val isSelected = option.id == activeModelId
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(
                        if (isSelected) Color.argb(48, 245, 158, 11)
                        else Color.argb(145, 15, 23, 42)
                    )
                    setStroke(
                        dp(1f),
                        if (isSelected) Color.parseColor("#F59E0B")
                        else Color.argb(95, 51, 65, 85)
                    )
                }
                setOnClickListener {
                    onSelectModel(activePreset, option.id)
                    onDismiss()
                }
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = option.label
                setTextColor(if (isSelected) Color.parseColor("#FCD34D") else Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = jakartaFont
                paint.isFakeBoldText = isSelected
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(labelView)

            if (option.label != option.id) {
                val idView = TextView(context).apply {
                    text = option.id
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    typeface = monoFont
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                textCol.addView(idView)
            }

            row.addView(
                textCol,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            if (isSelected) {
                val badge = TextView(context).apply {
                    text = "SEÇİLİ"
                    setTextColor(Color.parseColor("#1F1200"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = monoFont
                    paint.isFakeBoldText = true
                    setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6f).toFloat()
                        setColor(Color.parseColor("#F59E0B"))
                    }
                }
                row.addView(
                    badge,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8f)
                    }
                )
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) topMargin = dp(7f)
            }
            modelsContainer.addView(row, lp)
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()
}
