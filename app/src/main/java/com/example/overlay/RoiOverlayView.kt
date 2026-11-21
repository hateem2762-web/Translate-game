package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.InPlaceBlock
import com.example.model.RoiRect
import com.example.model.SubtitleFontSize

/**
 * Draggable & resizable Region of Interest (ROI) viewfinder overlay.
 * Also hosts the InPlaceOverlayLayer that renders frosted-dark masks and auto-fitted
 * translated text directly over the game's original text bounding boxes.
 */
@SuppressLint("ViewConstructor")
class RoiOverlayView(
    context: Context,
    private val onDragDelta: (dx: Int, dy: Int) -> Unit,
    private val onResizeDelta: (dw: Int, dh: Int) -> Unit,
    private val onInteractionFinished: (RoiRect) -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val topBarHeightPx = dp(28f)
    private val cornerHandleSizePx = dp(48f)
    private val insetCapturePx = dp(4f)

    private val dragPill: TextView
    private val reticleBox: ReticleView
    private val inPlaceLayer: InPlaceRenderView
    private val resizeGrip: ResizeGripView

    private var isLocked: Boolean = false

    init {
        clipChildren = false
        clipToPadding = false

        val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)

        dragPill = TextView(context).apply {
            text = context.getString(R.string.overlay_roi_handle)
            setTextColor(Color.parseColor("#E0F2FE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = jakartaFont
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC090E17"))
                cornerRadius = dp(12f).toFloat()
                setStroke(dp(1f), Color.parseColor("#9922D3EE"))
            }
        }
        addView(
            dragPill,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(24f)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )

        reticleBox = ReticleView(context)
        addView(
            reticleBox,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = topBarHeightPx
            }
        )

        inPlaceLayer = InPlaceRenderView(context, insetCapturePx)
        addView(
            inPlaceLayer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = topBarHeightPx
            }
        )

        resizeGrip = ResizeGripView(context)
        addView(
            resizeGrip,
            LayoutParams(cornerHandleSizePx, cornerHandleSizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        )

        setupDragListener(dragPill)
        setupResizeListener(resizeGrip)
    }

    fun getTopBarHeightPx(): Int = topBarHeightPx

    fun setLocked(locked: Boolean) {
        isLocked = locked
        // Keep INVISIBLE (not GONE) so reticleBox coordinates never shift on screen
        dragPill.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        resizeGrip.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        reticleBox.setLocked(locked)
    }

    fun updateInPlaceBlocks(blocks: List<InPlaceBlock>, fontSize: SubtitleFontSize = SubtitleFontSize.NORMAL) {
        inPlaceLayer.setBlocks(blocks, fontSize)
    }

    fun hasActiveInPlaceBlocks(): Boolean = inPlaceLayer.hasBlocks()

    fun setInPlaceStealth(hidden: Boolean) {
        inPlaceLayer.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Returns the exact screen-space rectangle of the transparent inner reticle area,
     * inset slightly so borders and brackets are never fed into OCR.
     */
    fun getCaptureRectOnScreen(): RoiRect {
        val loc = IntArray(2)
        reticleBox.getLocationOnScreen(loc)
        val w = (reticleBox.width - insetCapturePx * 2).coerceAtLeast(48)
        val h = (reticleBox.height - insetCapturePx * 2).coerceAtLeast(32)
        return RoiRect(
            x = (loc[0] + insetCapturePx).coerceAtLeast(0),
            y = (loc[1] + insetCapturePx).coerceAtLeast(0),
            width = w,
            height = h
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(target: View) {
        var lastRawX = 0f
        var lastRawY = 0f
        target.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onInteractionFinished(getCaptureRectOnScreen())
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeListener(target: View) {
        var lastRawX = 0f
        var lastRawY = 0f
        target.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false
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
                        onResizeDelta(dw, dh)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onInteractionFinished(getCaptureRectOnScreen())
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private class InPlaceRenderView(
        context: Context,
        private val insetCapturePx: Int
    ) : View(context) {
        private val renderer = InPlaceCanvasRenderer(context)
        private var blocks: List<InPlaceBlock> = emptyList()
        private var fontSize: SubtitleFontSize = SubtitleFontSize.NORMAL

        fun setBlocks(newBlocks: List<InPlaceBlock>, newFontSize: SubtitleFontSize) {
            blocks = newBlocks
            fontSize = newFontSize
            invalidate()
        }

        fun hasBlocks(): Boolean = blocks.isNotEmpty()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (blocks.isEmpty()) return
            renderer.drawBlocks(
                canvas = canvas,
                blocks = blocks,
                fontSize = fontSize,
                canvasWidth = width,
                canvasHeight = height,
                offsetX = insetCapturePx,
                offsetY = insetCapturePx
            )
        }
    }

    private class ReticleView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private var locked = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(16, 34, 211, 238)
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 34, 211, 238)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }

        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
        }

        fun setLocked(isLocked: Boolean) {
            locked = isLocked
            bracketPaint.alpha = if (isLocked) 85 else 255
            bracketPaint.strokeWidth = (if (isLocked) 1.8f else 3f) * density
            borderPaint.alpha = if (isLocked) 25 else 110
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val pad = 2f * density
            val cornerLen = (18f * density).coerceAtMost(minOf(w, h) * 0.28f)

            if (!locked) {
                canvas.drawRect(pad, pad, w - pad, h - pad, fillPaint)
            }
            canvas.drawRect(pad, pad, w - pad, h - pad, borderPaint)

            canvas.drawLine(pad, pad + cornerLen, pad, pad, bracketPaint)
            canvas.drawLine(pad, pad, pad + cornerLen, pad, bracketPaint)

            canvas.drawLine(w - pad - cornerLen, pad, w - pad, pad, bracketPaint)
            canvas.drawLine(w - pad, pad, w - pad, pad + cornerLen, bracketPaint)

            canvas.drawLine(pad, h - pad - cornerLen, pad, h - pad, bracketPaint)
            canvas.drawLine(pad, h - pad, pad + cornerLen, h - pad, bracketPaint)

            canvas.drawLine(w - pad - cornerLen, h - pad, w - pad, h - pad, bracketPaint)
            canvas.drawLine(w - pad, h - pad - cornerLen, w - pad, h - pad, bracketPaint)
        }
    }

    private class ResizeGripView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.FILL
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC090E17")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width - 14f * density
            val cy = height - 14f * density
            canvas.drawCircle(cx, cy, 12f * density, bgPaint)
            val r = 2.1f * density
            canvas.drawCircle(cx + 3f * density, cy + 3f * density, r, dotPaint)
            canvas.drawCircle(cx - 2f * density, cy + 3f * density, r, dotPaint)
            canvas.drawCircle(cx + 3f * density, cy - 2f * density, r, dotPaint)
        }
    }
}
