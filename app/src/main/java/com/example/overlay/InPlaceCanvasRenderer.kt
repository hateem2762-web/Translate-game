package com.example.overlay

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.InPlaceBlock
import com.example.model.SubtitleFontSize

/**
 * Shared high-quality frosted-glass blur + readable subtitle renderer used by both
 * [RoiOverlayView] (live overlay) and the in-app Sandbox preview.
 */
class InPlaceCanvasRenderer(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)

    private val maskRect = RectF()
    private val haloRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val clipPath = Path()
    private val cornerRadius = 16f * density

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(11f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val subtleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.75f * density
        color = Color.argb(26, 255, 255, 255)
    }

    private val fillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        typeface = jakartaFont
        isFakeBoldText = true
        setShadowLayer(4.5f * density, 0f, 1.5f * density, Color.argb(245, 0, 0, 0))
    }

    private val strokeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 4, 8, 15)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f * density
        isFakeBoldText = true
        typeface = jakartaFont
    }

    fun drawBlocks(
        canvas: Canvas,
        blocks: List<InPlaceBlock>,
        fontSize: SubtitleFontSize,
        canvasWidth: Int,
        canvasHeight: Int,
        offsetX: Int = 0,
        offsetY: Int = 0
    ) {
        if (blocks.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return

        val padX = 14f * density
        val padY = 8f * density
        val innerPadX = 10f * density
        val innerPadY = 6f * density
        val edgeMargin = 4f * density

        for (block in blocks) {
            if (block.translatedText.isBlank()) continue

            val origLeft = offsetX + block.boundingBox.x.toFloat()
            val origTop = offsetY + block.boundingBox.y.toFloat()
            val origW = block.boundingBox.width.toFloat().coerceAtLeast(48f * density)
            val origH = block.boundingBox.height.toFloat().coerceAtLeast(24f * density)
            val centerX = origLeft + origW / 2f
            val centerY = origTop + origH / 2f

            // Start with generous padded box over original text
            var boxW = (origW + padX * 2f).coerceAtMost(canvasWidth - edgeMargin * 2f)
            var availW = (boxW - innerPadX * 2f).toInt().coerceAtLeast(32)
            var boxH = (origH + padY * 2f)

            // Fit text respecting user minimum font size (never shrinking below readable floor)
            var chosenSp = fontSize.inPlaceTargetSp.coerceIn(fontSize.inPlaceMinSp, fontSize.inPlaceMaxSp)
            var layout = buildLayoutWithPaint(
                text = block.translatedText,
                paint = fillTextPaint.apply { textSize = chosenSp * scaledDensity },
                widthPx = availW
            )

            // If taller than initial box, first try stepping down slightly toward minSp
            while (layout.height + innerPadY * 2f > boxH && chosenSp > fontSize.inPlaceMinSp) {
                chosenSp = (chosenSp - 0.8f).coerceAtLeast(fontSize.inPlaceMinSp)
                layout = buildLayoutWithPaint(
                    text = block.translatedText,
                    paint = fillTextPaint.apply { textSize = chosenSp * scaledDensity },
                    widthPx = availW
                )
            }

            // If still overflowing at minimum readable font size, expand box horizontally first
            if (layout.height + innerPadY * 2f > boxH) {
                val maxExpandW = (canvasWidth - edgeMargin * 2f)
                    .coerceAtLeast(boxW)
                val desiredW = maxOf(boxW * 1.45f, (availW * 1.45f + innerPadX * 2f))
                    .coerceAtMost(maxExpandW)
                if (desiredW > boxW) {
                    boxW = desiredW
                    availW = (boxW - innerPadX * 2f).toInt().coerceAtLeast(32)
                    layout = buildLayoutWithPaint(
                        text = block.translatedText,
                        paint = fillTextPaint.apply { textSize = chosenSp * scaledDensity },
                        widthPx = availW
                    )
                }
            }

            // Expand vertically as needed so multi-line wrapped translation fits comfortably
            val neededBoxH = layout.height + innerPadY * 2f
            if (neededBoxH > boxH) {
                boxH = neededBoxH.coerceAtMost((canvasHeight - edgeMargin * 2f).coerceAtLeast(neededBoxH))
            }

            // Center expanded box smoothly around original bounding box, clamped inside canvas
            val left = (centerX - boxW / 2f)
                .coerceIn(edgeMargin, (canvasWidth - boxW - edgeMargin).coerceAtLeast(edgeMargin))
            val top = (centerY - boxH / 2f)
                .coerceIn(edgeMargin, (canvasHeight - boxH - edgeMargin).coerceAtLeast(edgeMargin))

            maskRect.set(left, top, left + boxW, top + boxH)

            // 1. Soft feathered outer halo so the frosted mask blends organically into game art
            haloRect.set(
                (maskRect.left - 3f * density).coerceAtLeast(0f),
                (maskRect.top - 2f * density).coerceAtLeast(0f),
                (maskRect.right + 3f * density).coerceAtMost(canvasWidth.toFloat()),
                (maskRect.bottom + 2f * density).coerceAtMost(canvasHeight.toFloat())
            )
            val baseColor = block.sampledBgColor
            haloPaint.color = Color.argb(
                (Color.alpha(baseColor) * 0.55f).toInt().coerceIn(60, 150),
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
            canvas.drawRoundRect(haloRect, cornerRadius + 4f * density, cornerRadius + 4f * density, haloPaint)

            // 2. Clipped frosted glass rounded container (RenderEffect Gaussian blur on API 31+ when HW accelerated)
            clipPath.reset()
            clipPath.addRoundRect(maskRect, cornerRadius, cornerRadius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)

            val patch = block.blurredPatch
            if (patch != null && !patch.isRecycled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
                    drawHardwareBlurredPatch(canvas, patch, maskRect)
                } else {
                    srcRect.set(0, 0, patch.width, patch.height)
                    canvas.drawBitmap(patch, srcRect, maskRect, bitmapPaint)
                }
            }

            val centerAlpha = Color.alpha(baseColor).coerceIn(175, 244)
            val edgeAlpha = (centerAlpha * 0.80f).toInt().coerceIn(135, 225)
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            scrimPaint.shader = LinearGradient(
                maskRect.left,
                maskRect.top,
                maskRect.left,
                maskRect.bottom,
                intArrayOf(
                    Color.argb(edgeAlpha, r, g, b),
                    Color.argb(centerAlpha, r, g, b),
                    Color.argb(edgeAlpha, r, g, b)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(maskRect, scrimPaint)
            scrimPaint.shader = null
            canvas.restore()

            canvas.drawRoundRect(maskRect, cornerRadius, cornerRadius, subtleRimPaint)

            // 3. Draw crisp readable translated text centered inside expanded frosted backdrop
            val textY = top + ((boxH - layout.height) / 2f).coerceAtLeast(innerPadY)
            canvas.save()
            canvas.translate(left + innerPadX, textY)
            val strokeLayout = buildLayoutWithPaint(
                text = block.translatedText,
                paint = strokeTextPaint.apply { textSize = fillTextPaint.textSize },
                widthPx = availW
            )
            strokeLayout.draw(canvas)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawHardwareBlurredPatch(canvas: Canvas, patch: android.graphics.Bitmap, dst: RectF) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val w = dst.width().toInt().coerceAtLeast(1)
        val h = dst.height().toInt().coerceAtLeast(1)
        val ok = runCatching {
            val node = RenderNode("inPlaceBlurPatch")
            node.setPosition(0, 0, w, h)
            node.setRenderEffect(RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.CLAMP))
            val rec = node.beginRecording(w, h)
            srcRect.set(0, 0, patch.width, patch.height)
            dstRect.set(0f, 0f, w.toFloat(), h.toFloat())
            rec.drawBitmap(patch, srcRect, dstRect, bitmapPaint)
            node.endRecording()

            canvas.save()
            canvas.translate(dst.left, dst.top)
            canvas.drawRenderNode(node)
            canvas.restore()
            node.discardDisplayList()
        }.isSuccess

        if (!ok) {
            srcRect.set(0, 0, patch.width, patch.height)
            canvas.drawBitmap(patch, srcRect, dst, bitmapPaint)
        }
    }

    private fun buildLayoutWithPaint(
        text: String,
        paint: TextPaint,
        widthPx: Int
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(2.0f * density, 1.08f)
            .setIncludePad(false)
            .build()
    }
}
