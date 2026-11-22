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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.capture.ScreenMetricsHelper
import com.example.model.AiProviderPreset
import com.example.model.TranslationEngineMode
import kotlin.math.roundToInt

/**
 * Floating 1-tap Translation Engine Picker shown directly over games when the user taps
 * the Engine badge inside the Classic Box HUD. Eliminates blind mode cycling and makes it
 * unmistakable whether Google Web, Bing Web, Baidu Web, Local ML Kit, AI, or Hybrid is active.
 */
@SuppressLint("ViewConstructor")
class EnginePickerOverlayView(
    context: Context,
    private val onSelectEngine: (TranslationEngineMode) -> Unit,
    private val onOpenModelPicker: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
    private val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

    private val listContainer: LinearLayout
    private val aiModelActionBtn: TextView

    private var activeEngine: TranslationEngineMode = TranslationEngineMode.OFFLINE_LOCAL
    private var activeAiPreset: AiProviderPreset = AiProviderPreset.GROQ
    private var activeAiModel: String = AiProviderPreset.GROQ.defaultModel

    init {
        setBackgroundColor(Color.argb(148, 3, 6, 12))
        setOnClickListener { onDismiss() }

        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val panelWidth = minOf(dp(360f), (bounds.width * 0.90f).roundToInt())
        val panelMaxHeight = minOf(dp(470f), (bounds.height * 0.74f).roundToInt())

        val cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            isClickable = true
            isFocusable = true
            elevation = dp(12f).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.argb(246, 9, 14, 23))
                cornerRadius = dp(18f).toFloat()
                setStroke(dp(1.2f), Color.parseColor("#6622D3EE"))
            }
        }

        // Header
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "Çeviri Motorunu Seç"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_hud_close)
            contentDescription = "Motor seçiciyi kapat"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setOnClickListener { onDismiss() }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(36f), dp(36f)))

        cardContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val subtitleView = TextView(context).apply {
            text = "Google / Bing / Baidu seçildiğinde asla cihaz içi ML Kit'e düşmez; hata varsa doğrudan gösterir."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = jakartaFont
        }
        cardContainer.addView(
            subtitleView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
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

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(
            listContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        cardContainer.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        aiModelActionBtn = TextView(context).apply {
            setTextColor(Color.parseColor("#FCD34D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.argb(40, 245, 158, 11))
                setStroke(dp(1f), Color.argb(140, 245, 158, 11))
            }
            visibility = View.GONE
            setOnClickListener {
                onDismiss()
                onOpenModelPicker()
            }
        }
        cardContainer.addView(
            aiModelActionBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            }
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
        engineMode: TranslationEngineMode,
        aiPreset: AiProviderPreset,
        aiModelName: String
    ) {
        activeEngine = engineMode
        activeAiPreset = aiPreset
        activeAiModel = aiModelName

        renderEngineRows()

        if (engineMode.usesAiModel()) {
            aiModelActionBtn.visibility = View.VISIBLE
            val shortModel = aiModelName.substringAfterLast('/').removeSuffix(":free")
            aiModelActionBtn.text = "AI Sağlayıcı & Model Değiştir (${aiPreset.shortLabelTr} • $shortModel)"
        } else {
            aiModelActionBtn.visibility = View.GONE
        }
    }

    private fun renderEngineRows() {
        listContainer.removeAllViews()
        TranslationEngineMode.entries.forEachIndexed { idx, mode ->
            val isSelected = mode == activeEngine
            val accent = Color.parseColor(mode.accentHex)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(
                        if (isSelected) Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent))
                        else Color.argb(145, 15, 23, 42)
                    )
                    setStroke(
                        dp(if (isSelected) 1.4f else 1f),
                        if (isSelected) accent else Color.argb(95, 51, 65, 85)
                    )
                }
                setOnClickListener {
                    onSelectEngine(mode)
                    onDismiss()
                }
            }

            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            }
            row.addView(
                dot,
                LinearLayout.LayoutParams(dp(10f), dp(10f)).apply {
                    marginEnd = dp(10f)
                }
            )

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleView = TextView(context).apply {
                text = when (mode) {
                    TranslationEngineMode.SMART_AI -> "${mode.fullTitleTr} • ${activeAiPreset.shortLabelTr}"
                    else -> mode.fullTitleTr
                }
                setTextColor(if (isSelected) accent else Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = jakartaFont
                paint.isFakeBoldText = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val descView = TextView(context).apply {
                text = engineDescription(mode)
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = jakartaFont
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(titleView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val tagText = if (isSelected) "SEÇİLİ" else mode.badgeLabelTr
            val badgeView = TextView(context).apply {
                text = tagText
                setTextColor(if (isSelected) Color.parseColor("#08101A") else accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = monoFont
                paint.isFakeBoldText = true
                setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(7f).toFloat()
                    setColor(
                        if (isSelected) accent
                        else Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent))
                    )
                }
            }
            row.addView(
                badgeView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                }
            )

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) topMargin = dp(7f)
            }
            listContainer.addView(row, lp)
        }
    }

    private fun engineDescription(mode: TranslationEngineMode): String {
        return when (mode) {
            TranslationEngineMode.GOOGLE_FREE -> "Doğrudan Google Translate Web NMT • ML Kit'e düşmez"
            TranslationEngineMode.BING_FREE -> "Ücretsiz web çeviri ucu • ML Kit'e düşmez"
            TranslationEngineMode.BAIDU_FREE -> "Çince/İngilizce ücretsiz web ucu • ML Kit'e düşmez"
            TranslationEngineMode.OFFLINE_LOCAL -> "Yalnızca cihaz içi ML Kit • İnternetsiz & kotasız"
            TranslationEngineMode.SMART_AI -> "LLM tabanlı doğal oyun çevirisi (${activeAiPreset.shortLabelTr})"
            TranslationEngineMode.HYBRID -> "Önce cihaz içi ML Kit, ardından AI ile düzeltme"
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()
}
