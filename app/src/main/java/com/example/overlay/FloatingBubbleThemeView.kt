package com.example.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import com.example.model.FloatingBubbleTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal data class BubbleThemeSpec(
    val accentColor: Int,
    val menuBgColor: Int,
    val menuBorderColor: Int,
    val chipHighlightBg: Int
)

internal fun bubbleThemeSpec(theme: FloatingBubbleTheme): BubbleThemeSpec = when (theme) {
    FloatingBubbleTheme.KWAI -> BubbleThemeSpec(
        accentColor = Color.parseColor("#FF4D6D"),
        menuBgColor = Color.argb(242, 22, 12, 24),
        menuBorderColor = Color.argb(180, 255, 93, 46),
        chipHighlightBg = Color.argb(64, 255, 77, 109)
    )
    FloatingBubbleTheme.SADE -> BubbleThemeSpec(
        accentColor = Color.parseColor("#E2E8F0"),
        menuBgColor = Color.argb(242, 20, 25, 34),
        menuBorderColor = Color.argb(135, 148, 163, 184),
        chipHighlightBg = Color.argb(56, 148, 163, 184)
    )
    FloatingBubbleTheme.NEON -> BubbleThemeSpec(
        accentColor = Color.parseColor("#22D3EE"),
        menuBgColor = Color.argb(242, 8, 13, 24),
        menuBorderColor = Color.argb(175, 34, 211, 238),
        chipHighlightBg = Color.argb(62, 34, 211, 238)
    )
    FloatingBubbleTheme.KEDI -> BubbleThemeSpec(
        accentColor = Color.parseColor("#FDA4AF"),
        menuBgColor = Color.argb(242, 34, 22, 39),
        menuBorderColor = Color.argb(175, 253, 164, 175),
        chipHighlightBg = Color.argb(62, 253, 164, 175)
    )
    FloatingBubbleTheme.KAPLUMBAGA -> BubbleThemeSpec(
        accentColor = Color.parseColor("#A3E635"),
        menuBgColor = Color.argb(242, 16, 30, 20),
        menuBorderColor = Color.argb(170, 163, 230, 53),
        chipHighlightBg = Color.argb(60, 163, 230, 53)
    )
}

/**
 * Custom Canvas-rendered circular floating control bubble supporting 5 handcrafted themes:
 * Kwai, Sade, Neon, Kedi (ears, whiskers & paw pad), and Kaplumbaga (hex shell & olive/emerald).
 */
@SuppressLint("ViewConstructor")
class ThemedBubbleButtonView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    var theme: FloatingBubbleTheme = FloatingBubbleTheme.NEON
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var statusDotColor: Int = Color.parseColor("#10B981")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val earPath = Path()
    private val innerEarPath = Path()
    private val hexPath = Path()
    private val arcRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Reserve subtle headroom at top so cat ears / turtle head bump fit without clipping
        val topHeadroom = 5.5f * density
        val radius = (min(w, h - topHeadroom) * 0.43f).coerceAtLeast(14f * density)
        val cx = w / 2f
        val cy = topHeadroom + (h - topHeadroom) / 2f

        when (theme) {
            FloatingBubbleTheme.KWAI -> drawKwaiBubble(canvas, cx, cy, radius)
            FloatingBubbleTheme.SADE -> drawSadeBubble(canvas, cx, cy, radius)
            FloatingBubbleTheme.NEON -> drawNeonBubble(canvas, cx, cy, radius)
            FloatingBubbleTheme.KEDI -> drawKediBubble(canvas, cx, cy, radius)
            FloatingBubbleTheme.KAPLUMBAGA -> drawKaplumbagaBubble(canvas, cx, cy, radius)
        }

        // Crisp live status indicator dot on bottom-right rim
        val dotR = 4.6f * density
        val dotX = cx + radius * 0.72f
        val dotY = cy + radius * 0.70f
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = Color.parseColor("#090E17")
        canvas.drawCircle(dotX, dotY, dotR + 1.4f * density, dotPaint)
        dotPaint.color = statusDotColor
        canvas.drawCircle(dotX, dotY, dotR, dotPaint)
    }

    private fun drawKwaiBubble(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Energetic outer shadow halo
        glowPaint.shader = null
        glowPaint.color = Color.argb(110, 255, 65, 108)
        glowPaint.strokeWidth = 4f * density
        canvas.drawCircle(cx, cy, r + 1f * density, glowPaint)

        // Vivid coral-orange to magenta diagonal gradient
        fillPaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(
                Color.parseColor("#FF6B2C"),
                Color.parseColor("#FF2E7E"),
                Color.parseColor("#FF8E3C")
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // Glossy top-left highlight crescent
        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeCap = Paint.Cap.ROUND
        detailPaint.strokeWidth = 2.2f * density
        detailPaint.color = Color.argb(145, 255, 255, 255)
        arcRect.set(cx - r * 0.76f, cy - r * 0.76f, cx + r * 0.76f, cy + r * 0.76f)
        canvas.drawArc(arcRect, 200f, 95f, false, detailPaint)

        // Crisp white badge border
        strokePaint.shader = null
        strokePaint.strokeWidth = 2.0f * density
        strokePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, strokePaint)

        // Playful yellow accent spark at top-left
        detailPaint.style = Paint.Style.FILL
        detailPaint.color = Color.parseColor("#FACC15")
        canvas.drawCircle(cx - r * 0.45f, cy - r * 0.44f, 3.2f * density, detailPaint)

        // Bold white center badge glyph (stylized play/translate prism)
        drawTranslateGlyph(canvas, cx, cy, r * 0.42f, Color.WHITE)
    }

    private fun drawSadeBubble(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Calm matte charcoal slate
        fillPaint.shader = null
        fillPaint.color = Color.argb(235, 28, 35, 48)
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Hairline minimalist rim
        strokePaint.shader = null
        strokePaint.strokeWidth = 1.25f * density
        strokePaint.color = Color.argb(165, 148, 163, 184)
        canvas.drawCircle(cx, cy, r, strokePaint)

        drawTranslateGlyph(canvas, cx, cy, r * 0.39f, Color.parseColor("#E2E8F0"))
    }

    private fun drawNeonBubble(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val neonSweep = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.parseColor("#00F5FF"),
                Color.parseColor("#A855F7"),
                Color.parseColor("#10B981"),
                Color.parseColor("#00F5FF")
            ),
            null
        )

        // Outer electric glow halo
        glowPaint.shader = neonSweep
        glowPaint.strokeWidth = 4.5f * density
        canvas.drawCircle(cx, cy, r + 1f * density, glowPaint)
        glowPaint.shader = null

        // Deep obsidian core
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            Color.parseColor("#0F1B33"),
            Color.parseColor("#060A13"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // Crisp electric neon border
        strokePaint.shader = neonSweep
        strokePaint.strokeWidth = 2.2f * density
        canvas.drawCircle(cx, cy, r, strokePaint)
        strokePaint.shader = null

        drawTranslateGlyph(canvas, cx, cy, r * 0.40f, Color.parseColor("#22D3EE"))
    }

    private fun drawKediBubble(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val bodyFill = Color.parseColor("#2E1F38")
        val earStroke = Color.parseColor("#FDA4AF")
        val innerPink = Color.parseColor("#FB7185")

        // Soft warm blush aura
        glowPaint.shader = null
        glowPaint.color = Color.argb(95, 253, 164, 175)
        glowPaint.strokeWidth = 3.5f * density
        canvas.drawCircle(cx, cy, r + 0.5f * density, glowPaint)

        // Left & Right pointed cat ears rising above the circle
        drawCatEar(canvas, cx, cy, r, side = -1f, bodyFill = bodyFill, strokeColor = earStroke, innerColor = innerPink)
        drawCatEar(canvas, cx, cy, r, side = 1f, bodyFill = bodyFill, strokeColor = earStroke, innerColor = innerPink)

        // Face circle
        fillPaint.shader = RadialGradient(
            cx, cy - r * 0.25f, r * 1.1f,
            Color.parseColor("#3D294A"),
            bodyFill,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        strokePaint.shader = null
        strokePaint.strokeWidth = 1.7f * density
        strokePaint.color = earStroke
        canvas.drawCircle(cx, cy, r, strokePaint)

        // Cute cat whiskers (3 left, 3 right)
        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeCap = Paint.Cap.ROUND
        detailPaint.strokeWidth = 1.35f * density
        detailPaint.color = Color.parseColor("#FECDD3")
        for (side in listOf(-1f, 1f)) {
            val startX = cx + side * r * 0.48f
            val endX = cx + side * r * 0.90f
            canvas.drawLine(startX, cy - r * 0.08f, endX, cy - r * 0.22f, detailPaint)
            canvas.drawLine(startX, cy + r * 0.06f, endX, cy + r * 0.06f, detailPaint)
            canvas.drawLine(startX, cy + r * 0.20f, endX, cy + r * 0.30f, detailPaint)
        }

        // Cute central paw pad (pati) + tiny pink toe beans
        detailPaint.style = Paint.Style.FILL
        detailPaint.color = Color.parseColor("#FFE4E6")
        canvas.drawOval(
            cx - r * 0.24f, cy + r * 0.04f,
            cx + r * 0.24f, cy + r * 0.36f,
            detailPaint
        )
        detailPaint.color = earStroke
        val toeR = r * 0.095f
        canvas.drawCircle(cx - r * 0.24f, cy - r * 0.14f, toeR, detailPaint)
        canvas.drawCircle(cx, cy - r * 0.24f, toeR, detailPaint)
        canvas.drawCircle(cx + r * 0.24f, cy - r * 0.14f, toeR, detailPaint)
    }

    private fun drawCatEar(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        side: Float,
        bodyFill: Int,
        strokeColor: Int,
        innerColor: Int
    ) {
        val baseInnerX = cx + side * r * 0.28f
        val baseOuterX = cx + side * r * 0.84f
        val baseY = cy - r * 0.56f
        val tipX = cx + side * r * 0.72f
        val tipY = cy - r * 1.24f

        earPath.reset()
        earPath.moveTo(baseInnerX, baseY)
        earPath.lineTo(tipX, tipY)
        earPath.lineTo(baseOuterX, cy - r * 0.22f)
        earPath.close()

        fillPaint.shader = null
        fillPaint.color = bodyFill
        canvas.drawPath(earPath, fillPaint)

        strokePaint.shader = null
        strokePaint.strokeWidth = 1.6f * density
        strokePaint.color = strokeColor
        canvas.drawPath(earPath, strokePaint)

        innerEarPath.reset()
        innerEarPath.moveTo(cx + side * r * 0.42f, cy - r * 0.54f)
        innerEarPath.lineTo(cx + side * r * 0.68f, cy - r * 1.03f)
        innerEarPath.lineTo(cx + side * r * 0.74f, cy - r * 0.38f)
        innerEarPath.close()
        detailPaint.style = Paint.Style.FILL
        detailPaint.color = innerColor
        canvas.drawPath(innerEarPath, detailPaint)
    }

    private fun drawKaplumbagaBubble(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val limeRim = Color.parseColor("#A3E635")
        val shellLine = Color.parseColor("#BEF264")

        // Cute little calm turtle head bump peek at top edge
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#4D7C0F")
        canvas.drawCircle(cx, cy - r * 0.96f, r * 0.24f, fillPaint)
        strokePaint.shader = null
        strokePaint.strokeWidth = 1.4f * density
        strokePaint.color = limeRim
        canvas.drawCircle(cx, cy - r * 0.96f, r * 0.24f, strokePaint)

        // Soft emerald shell aura
        glowPaint.shader = null
        glowPaint.color = Color.argb(88, 163, 230, 53)
        glowPaint.strokeWidth = 3.2f * density
        canvas.drawCircle(cx, cy, r + 0.5f * density, glowPaint)

        // Olive-to-emerald radial turtle shell gradient
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            Color.parseColor("#65A30D"),
            Color.parseColor("#14532D"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // Hexagonal scute shell pattern inside
        val hexR = r * 0.46f
        hexPath.reset()
        for (i in 0 until 6) {
            val angle = Math.toRadians((60 * i - 30).toDouble())
            val vx = cx + (hexR * cos(angle)).toFloat()
            val vy = cy + (hexR * sin(angle)).toFloat()
            if (i == 0) hexPath.moveTo(vx, vy) else hexPath.lineTo(vx, vy)
        }
        hexPath.close()

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = 1.3f * density
        detailPaint.color = Color.argb(175, Color.red(shellLine), Color.green(shellLine), Color.blue(shellLine))
        canvas.drawPath(hexPath, detailPaint)
        for (i in 0 until 6) {
            val angle = Math.toRadians((60 * i - 30).toDouble())
            val sx = cx + (hexR * cos(angle)).toFloat()
            val sy = cy + (hexR * sin(angle)).toFloat()
            val ex = cx + (r * 0.96f * cos(angle)).toFloat()
            val ey = cy + (r * 0.96f * sin(angle)).toFloat()
            canvas.drawLine(sx, sy, ex, ey, detailPaint)
        }

        // Durable lime-olive rim
        strokePaint.strokeWidth = 2.0f * density
        strokePaint.color = limeRim
        canvas.drawCircle(cx, cy, r, strokePaint)

        drawTranslateGlyph(canvas, cx, cy, r * 0.34f, Color.parseColor("#ECFCCB"))
    }

    private fun drawTranslateGlyph(canvas: Canvas, cx: Float, cy: Float, halfSize: Float, color: Int) {
        iconPaint.color = color
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 2.1f * density

        // Clean focus viewfinder frame + central subtitle lines
        val left = cx - halfSize
        val top = cy - halfSize * 0.78f
        val right = cx + halfSize
        val bottom = cy + halfSize * 0.78f
        val cLen = halfSize * 0.44f

        // 4 corner viewfinder brackets
        canvas.drawLine(left, top + cLen, left, top, iconPaint)
        canvas.drawLine(left, top, left + cLen, top, iconPaint)

        canvas.drawLine(right - cLen, top, right, top, iconPaint)
        canvas.drawLine(right, top, right, top + cLen, iconPaint)

        canvas.drawLine(left, bottom - cLen, left, bottom, iconPaint)
        canvas.drawLine(left, bottom, left + cLen, bottom, iconPaint)

        canvas.drawLine(right - cLen, bottom, right, bottom, iconPaint)
        canvas.drawLine(right, bottom, right, bottom - cLen, iconPaint)

        // Clean centered subtitle bars
        canvas.drawLine(cx - halfSize * 0.56f, cy - halfSize * 0.16f, cx + halfSize * 0.56f, cy - halfSize * 0.16f, iconPaint)
        canvas.drawLine(cx - halfSize * 0.36f, cy + halfSize * 0.24f, cx + halfSize * 0.36f, cy + halfSize * 0.24f, iconPaint)
    }
}

/**
 * Edge-docked right-side Half-Circle ("yarım yuvarlak") dock tab showing a crisp "<" arrow,
 * styled identically to the active [FloatingBubbleTheme].
 */
@SuppressLint("ViewConstructor")
class ThemedHalfCircleDockView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    var theme: FloatingBubbleTheme = FloatingBubbleTheme.NEON
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var statusDotColor: Int = Color.parseColor("#10B981")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dockPath = Path()
    private val earPath = Path()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val topPad = if (theme == FloatingBubbleTheme.KEDI) 6f * density else 3f * density
        val botPad = 3f * density
        val leftPad = 4f * density
        val arcTop = topPad
        val arcBottom = h - botPad
        val arcDiameter = (arcBottom - arcTop).coerceAtLeast(24f * density)
        val radius = arcDiameter / 2f
        val cy = (arcTop + arcBottom) / 2f

        // Semicircle anchored flush at right edge (x = w), bulging cleanly leftward
        dockPath.reset()
        dockPath.moveTo(w, arcTop)
        dockPath.arcTo(
            leftPad,
            arcTop,
            (leftPad + arcDiameter).coerceAtLeast(w * 1.6f),
            arcBottom,
            -90f,
            -180f,
            false
        )
        dockPath.lineTo(w, arcBottom)
        dockPath.close()

        when (theme) {
            FloatingBubbleTheme.KWAI -> {
                glowPaint.shader = null
                glowPaint.color = Color.argb(105, 255, 65, 108)
                glowPaint.strokeWidth = 3.5f * density
                canvas.drawPath(dockPath, glowPaint)

                fillPaint.shader = LinearGradient(
                    leftPad, arcTop, w, arcBottom,
                    Color.parseColor("#FF6B2C"),
                    Color.parseColor("#FF2E7E"),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(dockPath, fillPaint)
                fillPaint.shader = null

                strokePaint.shader = null
                strokePaint.strokeWidth = 1.8f * density
                strokePaint.color = Color.WHITE
                canvas.drawPath(dockPath, strokePaint)

                drawChevron(canvas, w * 0.52f, cy, radius * 0.34f, Color.WHITE)
            }

            FloatingBubbleTheme.SADE -> {
                fillPaint.shader = null
                fillPaint.color = Color.argb(232, 28, 35, 48)
                canvas.drawPath(dockPath, fillPaint)

                strokePaint.shader = null
                strokePaint.strokeWidth = 1.2f * density
                strokePaint.color = Color.argb(165, 148, 163, 184)
                canvas.drawPath(dockPath, strokePaint)

                drawChevron(canvas, w * 0.52f, cy, radius * 0.33f, Color.parseColor("#E2E8F0"))
            }

            FloatingBubbleTheme.NEON -> {
                val neonShader = LinearGradient(
                    leftPad, arcTop, leftPad, arcBottom,
                    intArrayOf(
                        Color.parseColor("#00F5FF"),
                        Color.parseColor("#A855F7"),
                        Color.parseColor("#10B981")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                glowPaint.shader = neonShader
                glowPaint.strokeWidth = 4f * density
                canvas.drawPath(dockPath, glowPaint)
                glowPaint.shader = null

                fillPaint.shader = null
                fillPaint.color = Color.argb(242, 6, 10, 19)
                canvas.drawPath(dockPath, fillPaint)

                strokePaint.shader = neonShader
                strokePaint.strokeWidth = 2.0f * density
                canvas.drawPath(dockPath, strokePaint)
                strokePaint.shader = null

                drawChevron(canvas, w * 0.52f, cy, radius * 0.35f, Color.parseColor("#22D3EE"))
            }

            FloatingBubbleTheme.KEDI -> {
                // Cute mini cat ear peeking at top-left curve of the half circle
                earPath.reset()
                earPath.moveTo(w - radius * 0.55f, arcTop + 2f * density)
                earPath.lineTo(w - radius * 0.85f, arcTop - 6.5f * density)
                earPath.lineTo(w - radius * 0.10f, arcTop + 1f * density)
                earPath.close()
                fillPaint.shader = null
                fillPaint.color = Color.parseColor("#2E1F38")
                canvas.drawPath(earPath, fillPaint)
                strokePaint.shader = null
                strokePaint.strokeWidth = 1.4f * density
                strokePaint.color = Color.parseColor("#FDA4AF")
                canvas.drawPath(earPath, strokePaint)

                glowPaint.shader = null
                glowPaint.color = Color.argb(90, 253, 164, 175)
                glowPaint.strokeWidth = 3f * density
                canvas.drawPath(dockPath, glowPaint)

                fillPaint.color = Color.parseColor("#2E1F38")
                canvas.drawPath(dockPath, fillPaint)

                strokePaint.strokeWidth = 1.6f * density
                strokePaint.color = Color.parseColor("#FDA4AF")
                canvas.drawPath(dockPath, strokePaint)

                // Tiny cute whisker ticks
                detailPaint.style = Paint.Style.STROKE
                detailPaint.strokeWidth = 1.1f * density
                detailPaint.color = Color.parseColor("#FECDD3")
                canvas.drawLine(w - 6f * density, cy - 8f * density, w - 1f * density, cy - 10f * density, detailPaint)
                canvas.drawLine(w - 6f * density, cy + 8f * density, w - 1f * density, cy + 10f * density, detailPaint)

                drawChevron(canvas, w * 0.50f, cy, radius * 0.34f, Color.parseColor("#FFE4E6"))
            }

            FloatingBubbleTheme.KAPLUMBAGA -> {
                glowPaint.shader = null
                glowPaint.color = Color.argb(85, 163, 230, 53)
                glowPaint.strokeWidth = 3f * density
                canvas.drawPath(dockPath, glowPaint)

                fillPaint.shader = LinearGradient(
                    leftPad, cy, w, cy,
                    Color.parseColor("#4D7C0F"),
                    Color.parseColor("#14532D"),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(dockPath, fillPaint)
                fillPaint.shader = null

                // Subtle turtle shell facet lines inside dock
                detailPaint.style = Paint.Style.STROKE
                detailPaint.strokeWidth = 1.1f * density
                detailPaint.color = Color.argb(145, 190, 242, 100)
                canvas.drawLine(w - radius * 0.45f, arcTop + 6f * density, w - radius * 0.9f, cy, detailPaint)
                canvas.drawLine(w - radius * 0.9f, cy, w - radius * 0.45f, arcBottom - 6f * density, detailPaint)

                strokePaint.shader = null
                strokePaint.strokeWidth = 1.8f * density
                strokePaint.color = Color.parseColor("#A3E635")
                canvas.drawPath(dockPath, strokePaint)

                drawChevron(canvas, w * 0.52f, cy, radius * 0.34f, Color.parseColor("#ECFCCB"))
            }
        }

        // Tiny status indicator dot near bottom of dock
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = statusDotColor
        canvas.drawCircle(w - 7f * density, arcBottom - 8f * density, 3.2f * density, dotPaint)
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        chevronPaint.color = color
        chevronPaint.strokeWidth = 2.6f * density
        val tipX = cx - size * 0.42f
        val armX = cx + size * 0.42f
        canvas.drawLine(armX, cy - size * 0.72f, tipX, cy, chevronPaint)
        canvas.drawLine(tipX, cy, armX, cy + size * 0.72f, chevronPaint)
    }
}
