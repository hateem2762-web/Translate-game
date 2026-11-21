package com.example.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
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
import com.example.model.SubtitleEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact, dark, minimalist scrollable overlay window displaying chronological session dialogue
 * history above the game. Supports single-tap copy to clipboard, clearing history, and immediate
 * dismissal on outside tap or close button.
 */
@SuppressLint("ViewConstructor")
class DialogueHistoryOverlayView(
    context: Context,
    private val onClearHistory: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
    private val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scrollView: ScrollView
    private val listContainer: LinearLayout
    private val emptyTextView: TextView
    private val statusToastView: TextView
    private val clearButton: TextView

    private val hideToastRunnable = Runnable {
        statusToastView.visibility = View.GONE
    }

    init {
        // Soft full-screen scrim so tapping anywhere outside the compact panel closes it cleanly
        setBackgroundColor(Color.argb(115, 4, 7, 13))
        setOnClickListener { onDismiss() }

        val bounds = ScreenMetricsHelper.getScreenBounds(context)
        val panelWidth = minOf((bounds.width * 0.90f).toInt(), dp(390f)).coerceAtLeast(dp(280f))
        val panelMaxHeight = (bounds.height * 0.62f).toInt().coerceAtLeast(dp(240f))

        val cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.argb(244, 10, 15, 26))
                cornerRadius = dp(18f).toFloat()
                setStroke(dp(1f), Color.parseColor("#6622D3EE"))
            }
            elevation = dp(12f).toFloat()
        }

        // Header Bar
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = ImageView(context).apply {
            setImageResource(R.drawable.ic_hud_history)
            setColorFilter(Color.parseColor("#22D3EE"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        headerRow.addView(iconView, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
            marginEnd = dp(8f)
        })

        val titleView = TextView(context).apply {
            text = "Diyalog Geçmişi"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = jakartaFont
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        clearButton = TextView(context).apply {
            text = "Geçmişi Temizle"
            setTextColor(Color.parseColor("#FDA4AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.argb(40, 244, 63, 94))
                setStroke(dp(1f), Color.argb(95, 251, 113, 133))
            }
            setOnClickListener {
                onClearHistory()
                showFeedback("Geçmiş temizlendi")
            }
        }
        headerRow.addView(clearButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(6f)
        })

        val closeButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_hud_close)
            contentDescription = "Diyalog geçmişini kapat"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { onDismiss() }
        }
        headerRow.addView(closeButton, LinearLayout.LayoutParams(dp(38f), dp(38f)))

        cardContainer.addView(
            headerRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        statusToastView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = monoFont
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(Color.argb(38, 16, 185, 129))
            }
        }
        cardContainer.addView(
            statusToastView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
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

        emptyTextView = TextView(context).apply {
            text = "Bu oturumda henüz yakalanan diyalog yok.\nCümleler çevrildikçe burada kronolojik olarak birikir."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = jakartaFont
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(24f), dp(12f), dp(24f))
        }
        cardContainer.addView(
            emptyTextView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        scrollView = ScrollView(context).apply {
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

        val hintFooter = TextView(context).apply {
            text = "Kopyalamak için bir satıra dokunun • Kapatmak için dışarı dokunun"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = jakartaFont
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, 0)
        }
        cardContainer.addView(
            hintFooter,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        addView(
            cardContainer,
            LayoutParams(panelWidth, panelMaxHeight).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    fun renderEntries(entries: List<SubtitleEntry>, scrollToLatest: Boolean = false) {
        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            emptyTextView.visibility = View.VISIBLE
            scrollView.visibility = View.GONE
            clearButton.alpha = 0.45f
            clearButton.isEnabled = false
            return
        }

        emptyTextView.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
        clearButton.alpha = 1f
        clearButton.isEnabled = true

        entries.forEachIndexed { idx, entry ->
            val itemView = createEntryCard(entry)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) topMargin = dp(8f)
            }
            listContainer.addView(itemView, lp)
        }

        if (scrollToLatest) {
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun createEntryCard(entry: SubtitleEntry): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11f), dp(9f), dp(11f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.argb(190, 19, 28, 46))
                setStroke(dp(1f), Color.argb(50, 148, 163, 184))
            }
            setOnClickListener {
                copyToClipboard(entry.translatedText)
                showFeedback("Panoya kopyalandı")
            }
        }

        val topMetaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val sourceView = TextView(context).apply {
            text = entry.sourceText
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = jakartaFont
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        topMetaRow.addView(
            sourceView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8f)
            }
        )

        val timeView = TextView(context).apply {
            text = timeFormat.format(Date(entry.timestampMs))
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = monoFont
            maxLines = 1
        }
        topMetaRow.addView(
            timeView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        card.addView(
            topMetaRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val translatedView = TextView(context).apply {
            text = entry.translatedText
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = jakartaFont
            setLineSpacing(dp(2f).toFloat(), 1.05f)
        }
        card.addView(
            translatedView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            }
        )

        return card
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("SubLens Çeviri", text))
    }

    private fun showFeedback(message: String) {
        removeCallbacks(hideToastRunnable)
        statusToastView.text = message
        statusToastView.visibility = View.VISIBLE
        postDelayed(hideToastRunnable, 1800L)
    }

    private fun dp(value: Float): Int = (value * density).toInt()
}
