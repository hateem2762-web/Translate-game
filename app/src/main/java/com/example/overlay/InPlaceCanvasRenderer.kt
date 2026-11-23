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
import com.example.model.BlurBackdropStyle
import com.example.model.InPlaceBlock
import com.example.model.TranslatorTelemetry
import kotlin.math.max
import kotlin.math.min

data class InPlaceRenderStyle(
    val fontSizeSp: Float = 18f,
    val paddingXdp: Float = 10f,
    val paddingYdp: Float = 5f,
    val cornerRadiusDp: Float = 12f,
    val offsetXdp: Float = 0f,
    val offsetYdp: Float = 0f,
    val textColorArgb: Int = Color.WHITE,
    val blurStyle: BlurBackdropStyle = BlurBackdropStyle.DARK_FROSTED,
    val blurIntensity: Float = 0.70f
) {
    companion object {
        fun fromTelemetry(t: TranslatorTelemetry): InPlaceRenderStyle = InPlaceRenderStyle(
            fontSizeSp = t.fontSizeSp,
            paddingXdp = t.inPlacePaddingXdp,
            paddingYdp = t.inPlacePaddingYdp,
            cornerRadiusDp = t.inPlaceCornerRadiusDp,
            offsetXdp = t.inPlaceOffsetXdp,
            offsetYdp = t.inPlaceOffsetYdp,
            textColorArgb = t.subtitleTextColorArgb,
            blurStyle = t.inPlaceBlurStyle,
            blurIntensity = t.inPlaceBlurIntensity
        )
    }
}

/**
 * Pure geometry solver ensuring vertically stacked multi-line In-Place blocks never overlap
 * each other or collide near ROI edges.
 */
object InPlaceLayoutSolver {
    data class MutableRect(
        var left: Float,
        var top: Float,
        val width: Float,
        val height: Float
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    /**
     * Resolves vertical collisions among [rects] (expected pre-sorted top-to-bottom).
     * Returns `true` if all rectangles fit cleanly inside `[minTop, maxBottom]` with
     * at least [minGapPx] between horizontally overlapping blocks; `false` if the stack
     * still overflows available vertical space (signaling the caller to tighten font/padding).
     */
    fun resolveVerticalCollisions(
        rects: List<MutableRect>,
        minGapPx: Float,
        minTop: Float,
        maxBottom: Float
    ): Boolean {
        if (rects.isEmpty()) return true

        for (r in rects) {
            r.top = r.top.coerceIn(minTop, (maxBottom - r.height).coerceAtLeast(minTop))
        }

        // 1. Top-down pass: guarantee any block below another horizontally-overlapping block starts below it
        for (j in rects.indices) {
            var requiredTop = rects[j].top
            for (i in 0 until j) {
                if (horizontallyOverlaps(rects[i], rects[j])) {
                    requiredTop = max(requiredTop, rects[i].bottom + minGapPx)
                }
            }
            rects[j].top = requiredTop
        }

        val currentMaxBottom = rects.maxOf { it.bottom }
        if (currentMaxBottom <= maxBottom + 0.5f) {
            return true
        }

        // 2. Bottom-up shift: pull overflowing stack upward into available free space at the top
        val excess = currentMaxBottom - maxBottom
        for (i in rects.indices.reversed()) {
            rects[i].top -= excess
        }
        // Re-enforce tight minimum gaps bottom-up so trailing blocks don't press past maxBottom
        var bottomBlockMaxBottom = maxBottom
        for (j in rects.indices.reversed()) {
            if (rects[j].bottom > bottomBlockMaxBottom) {
                rects[j].top = bottomBlockMaxBottom - rects[j].height
            }
            for (i in (j - 1) downTo 0) {
                if (horizontallyOverlaps(rects[i], rects[j]) && rects[i].bottom + minGapPx > rects[j].top) {
                    rects[i].top = rects[j].top - minGapPx - rects[i].height
                }
            }
            bottomBlockMaxBottom = rects[j].top - minGapPx
        }

        val minTopAfterPull = rects.minOf { it.top }
        if (minTopAfterPull >= minTop - 0.5f) {
            return rects.maxOf { it.bottom } <= maxBottom + 0.5f
        }

        // 3. Didn't fit without clipping top -> anchor top at minTop and keep blocks strictly non-overlapping
        val topDeficit = minTop - minTopAfterPull
        for (r in rects) {
            r.top += topDeficit
        }
        for (j in rects.indices) {
            var requiredTop = max(minTop, rects[j].top)
            for (i in 0 until j) {
                if (horizontallyOverlaps(rects[i], rects[j])) {
                    requiredTop = max(requiredTop, rects[i].bottom + minGapPx)
                }
            }
            rects[j].top = requiredTop
        }
        return false
    }

    fun horizontallyOverlaps(a: MutableRect, b: MutableRect): Boolean {
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        return overlap > 2f
    }
}

/**
 * Shared high-quality frosted-glass blur + readable subtitle renderer used by both
 * [RoiOverlayView] (live overlay) and the in-app Sandbox preview.
 * Honors fine-grained geometry controls (padding X/Y, corner radius, offset X/Y, and 1sp font size),
 * scales multi-line blocks cleanly, and resolves vertical collisions so 2–3+ lines never overlap.
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

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(4.5f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val subtleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.75f * density
        color = Color.argb(12, 255, 255, 255)
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
        strokeWidth = 3.0f * density
        isFakeBoldText = true
        typeface = jakartaFont
    }

    private data class PlacedBlock(
        val block: InPlaceBlock,
        val rect: InPlaceLayoutSolver.MutableRect,
        val layout: StaticLayout,
        val chosenSp: Float,
        val availW: Int,
        val innerPadX: Float,
        val innerPadY: Float
    )

    fun drawBlocks(
        canvas: Canvas,
        blocks: List<InPlaceBlock>,
        style: InPlaceRenderStyle,
        canvasWidth: Int,
        canvasHeight: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        outDrawnRects: MutableList<com.example.model.RoiRect>? = null
    ) {
        outDrawnRects?.clear()
        val visibleBlocks = blocks
            .filter { it.translatedText.isNotBlank() }
            .sortedWith(compareBy<InPlaceBlock> { it.boundingBox.y }.thenBy { it.boundingBox.x })
        if (visibleBlocks.isEmpty() || canvasWidth <= 0 || canvasHeight <= 0) return

        fillTextPaint.color = style.textColorArgb

        val padX = style.paddingXdp * density
        val rawPadY = style.paddingYdp * density
        val radius = (style.cornerRadiusDp * density).coerceAtLeast(0f)
        val userShiftX = style.offsetXdp * density
        val userShiftY = style.offsetYdp * density

        val innerPadX = (style.paddingXdp.coerceAtLeast(3f) * 0.7f * density)
            .coerceIn(3f * density, 14f * density)
        val baseInnerPadY = (style.paddingYdp.coerceAtLeast(2f) * 0.65f * density)
            .coerceIn(2f * density, 10f * density)
        val edgeMargin = 3f * density
        val minBlockGapPx = 4f * density

        val targetSp = style.fontSizeSp.coerceIn(12f, 32f)
        val hardMinSp = 9.5f

        var capSp = targetSp
        var compressionLevel = 0
        var placedList: List<PlacedBlock> = emptyList()

        // Try at user's target font size first; if stacked lines collide/overflow canvas,
        // tighten inter-block padding slightly and progressively reduce cap font size until all fit cleanly.
        while (true) {
            val effectivePadY = when {
                visibleBlocks.size <= 1 -> rawPadY
                compressionLevel == 0 -> rawPadY
                compressionLevel == 1 -> min(rawPadY, 3f * density)
                else -> min(rawPadY, 1.5f * density)
            }
            val effectiveInnerPadY = when {
                compressionLevel == 0 -> baseInnerPadY
                compressionLevel == 1 -> min(baseInnerPadY, 3.5f * density)
                else -> 2f * density
            }

            placedList = layoutCandidateBlocks(
                blocks = visibleBlocks,
                capSp = capSp,
                hardMinSp = hardMinSp,
                padX = padX,
                padY = effectivePadY,
                innerPadX = innerPadX,
                innerPadY = effectiveInnerPadY,
                userShiftX = userShiftX,
                userShiftY = userShiftY,
                edgeMargin = edgeMargin,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                offsetX = offsetX,
                offsetY = offsetY
            )

            val rects = placedList.map { it.rect }
            val fits = InPlaceLayoutSolver.resolveVerticalCollisions(
                rects = rects,
                minGapPx = minBlockGapPx,
                minTop = edgeMargin,
                maxBottom = canvasHeight - edgeMargin
            )
            if (fits || (capSp <= hardMinSp && compressionLevel >= 2)) {
                break
            }
            if (visibleBlocks.size > 1 && compressionLevel < 2 && capSp == targetSp) {
                compressionLevel++
            } else if (capSp > hardMinSp) {
                capSp = (capSp - 1.5f).coerceAtLeast(hardMinSp)
                if (compressionLevel < 2) compressionLevel++
            } else {
                compressionLevel++
            }
        }

        val haloSpread = 2.5f * density
        for (placed in placedList) {
            val block = placed.block
            val rect = placed.rect
            val left = rect.left
            val top = rect.top
            val boxW = rect.width
            val boxH = rect.height

            outDrawnRects?.add(
                com.example.model.RoiRect(
                    x = (left - offsetX - haloSpread).toInt().coerceAtLeast(0),
                    y = (top - offsetY - haloSpread).toInt().coerceAtLeast(0),
                    width = (boxW + haloSpread * 2f).toInt().coerceAtLeast(16),
                    height = (boxH + haloSpread * 2f).toInt().coerceAtLeast(16)
                )
            )

            val maxRadius = minOf(boxW, boxH) / 2f
            val clampedRadius = radius.coerceAtMost(maxRadius)

            maskRect.set(left, top, left + boxW, top + boxH)
            val isRealBlur = style.blurStyle == BlurBackdropStyle.REAL_BLUR

            if (!isRealBlur) {
                // 1. Soft outer feathered halo so dark frosted edges blend into the game scene
                haloRect.set(
                    (maskRect.left - haloSpread).coerceAtLeast(0f),
                    (maskRect.top - haloSpread).coerceAtLeast(0f),
                    (maskRect.right + haloSpread).coerceAtMost(canvasWidth.toFloat()),
                    (maskRect.bottom + haloSpread).coerceAtMost(canvasHeight.toFloat())
                )
                val baseColor = block.sampledBgColor
                haloPaint.color = Color.argb(
                    (Color.alpha(baseColor) * 0.45f).toInt().coerceIn(48, 125),
                    Color.red(baseColor),
                    Color.green(baseColor),
                    Color.blue(baseColor)
                )
                val haloRadius = if (clampedRadius > 0f) clampedRadius + haloSpread else 0f
                canvas.drawRoundRect(haloRect, haloRadius, haloRadius, haloPaint)
            }

            // 2. Clipped backdrop rounded container
            clipPath.reset()
            clipPath.addRoundRect(maskRect, clampedRadius, clampedRadius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)

            val patch = block.blurredPatch
            if (patch != null && !patch.isRecycled) {
                val gpuBlurRadius = if (isRealBlur) {
                    6f + style.blurIntensity.coerceIn(0.20f, 1.0f) * 20f
                } else {
                    24f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) {
                    drawHardwareBlurredPatch(canvas, patch, maskRect, gpuBlurRadius)
                } else {
                    srcRect.set(0, 0, patch.width, patch.height)
                    canvas.drawBitmap(patch, srcRect, maskRect, bitmapPaint)
                }
            }

            if (!isRealBlur) {
                val baseColor = block.sampledBgColor
                val centerAlpha = Color.alpha(baseColor).coerceIn(175, 244)
                val edgeAlpha = (centerAlpha * 0.82f).toInt().coerceIn(140, 225)
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
            } else if (patch == null || patch.isRecycled) {
                // Fallback only if no snapshot patch was available (e.g., synthetic test)
                scrimPaint.color = Color.argb(110, 10, 14, 22)
                canvas.drawRect(maskRect, scrimPaint)
            }
            canvas.restore()

            if (!isRealBlur && clampedRadius > 0f) {
                canvas.drawRoundRect(maskRect, clampedRadius, clampedRadius, subtleRimPaint)
            }

            // 3. Draw crisp readable translated text centered inside frosted backdrop
            val textY = top + ((boxH - placed.layout.height) / 2f).coerceAtLeast(placed.innerPadY)
            canvas.save()
            canvas.translate(left + placed.innerPadX, textY)
            strokeTextPaint.textSize = placed.chosenSp * scaledDensity
            strokeTextPaint.strokeWidth = (placed.chosenSp / 18f * 2.8f * density)
                .coerceIn(1.5f * density, 3.2f * density)
            val strokeLayout = buildLayoutWithPaint(
                text = block.translatedText,
                paint = strokeTextPaint,
                widthPx = placed.availW
            )
            strokeLayout.draw(canvas)
            placed.layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun layoutCandidateBlocks(
        blocks: List<InPlaceBlock>,
        capSp: Float,
        hardMinSp: Float,
        padX: Float,
        padY: Float,
        innerPadX: Float,
        innerPadY: Float,
        userShiftX: Float,
        userShiftY: Float,
        edgeMargin: Float,
        canvasWidth: Int,
        canvasHeight: Int,
        offsetX: Int,
        offsetY: Int
    ): List<PlacedBlock> {
        val maxBoxW = (canvasWidth - edgeMargin * 2f).coerceAtLeast(48f * density)
        val maxBoxH = (canvasHeight - edgeMargin * 2f).coerceAtLeast(28f * density)
        val blockMinSp = (capSp - 3.5f).coerceAtLeast(hardMinSp)
        val isSingleBlock = blocks.size == 1

        return blocks.map { block ->
            val origLeft = offsetX + block.boundingBox.x.toFloat() + userShiftX
            val origTop = offsetY + block.boundingBox.y.toFloat() + userShiftY
            val origW = block.boundingBox.width.toFloat().coerceAtLeast(40f * density)
            val origH = block.boundingBox.height.toFloat().coerceAtLeast(20f * density)
            val centerX = origLeft + origW / 2f
            val centerY = origTop + origH / 2f

            var boxW = (origW + padX * 2f).coerceIn(48f * density, maxBoxW)
            var boxH = (origH + padY * 2f).coerceAtLeast(22f * density)

            var chosenSp = capSp
            fillTextPaint.textSize = chosenSp * scaledDensity
            val naturalLineW = Layout.getDesiredWidth(block.translatedText, fillTextPaint)
            if (isSingleBlock && !block.translatedText.contains('\n')) {
                val desiredSingleW = (naturalLineW + innerPadX * 2f + 4f * density).coerceAtMost(maxBoxW)
                if (desiredSingleW > boxW && desiredSingleW <= maxOf(boxW * 1.45f, maxBoxW * 0.88f)) {
                    boxW = desiredSingleW
                }
            }

            var availW = (boxW - innerPadX * 2f).toInt().coerceAtLeast(28)
            var layout = buildLayoutWithPaint(
                text = block.translatedText,
                paint = fillTextPaint,
                widthPx = availW
            )

            while (layout.height + innerPadY * 2f > boxH && chosenSp > blockMinSp) {
                chosenSp = (chosenSp - 0.8f).coerceAtLeast(blockMinSp)
                layout = buildLayoutWithPaint(
                    text = block.translatedText,
                    paint = fillTextPaint.apply { textSize = chosenSp * scaledDensity },
                    widthPx = availW
                )
            }

            // Expand width smoothly before overflowing height onto neighboring lines
            if (layout.height + innerPadY * 2f > boxH && boxW < maxBoxW) {
                val desiredW = (boxW * 1.32f).coerceAtMost(maxBoxW)
                if (desiredW > boxW + 2f) {
                    boxW = desiredW
                    availW = (boxW - innerPadX * 2f).toInt().coerceAtLeast(28)
                    layout = buildLayoutWithPaint(
                        text = block.translatedText,
                        paint = fillTextPaint.apply { textSize = chosenSp * scaledDensity },
                        widthPx = availW
                    )
                }
            }

            // If a single block still overflows available canvas height, shrink font further down to hardMinSp
            while (layout.height + innerPadY * 2f > maxBoxH && chosenSp > hardMinSp) {
                chosenSp = (chosenSp - 0.8f).coerceAtLeast(hardMinSp)
                layout = buildLayoutWithPaint(
                    text = block.translatedText,
                    paint = fillTextPaint.apply { textSize = chosenSp * scaledDensity },
                    widthPx = availW
                )
            }

            val neededBoxH = layout.height + innerPadY * 2f
            if (neededBoxH > boxH) {
                boxH = neededBoxH.coerceAtMost(maxBoxH.coerceAtLeast(neededBoxH))
            }

            val left = (centerX - boxW / 2f)
                .coerceIn(edgeMargin, (canvasWidth - boxW - edgeMargin).coerceAtLeast(edgeMargin))
            val top = (centerY - boxH / 2f)
                .coerceIn(edgeMargin, (canvasHeight - boxH - edgeMargin).coerceAtLeast(edgeMargin))

            PlacedBlock(
                block = block,
                rect = InPlaceLayoutSolver.MutableRect(left, top, boxW, boxH),
                layout = layout,
                chosenSp = chosenSp,
                availW = availW,
                innerPadX = innerPadX,
                innerPadY = innerPadY
            )
        }
    }

    private fun drawHardwareBlurredPatch(
        canvas: Canvas,
        patch: android.graphics.Bitmap,
        dst: RectF,
        blurRadiusPx: Float = 24f
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val w = dst.width().toInt().coerceAtLeast(1)
        val h = dst.height().toInt().coerceAtLeast(1)
        val radius = blurRadiusPx.coerceIn(4f, 36f)
        val ok = runCatching {
            val node = RenderNode("inPlaceBlurPatch")
            node.setPosition(0, 0, w, h)
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
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
            .setLineSpacing(1.5f * density, 1.06f)
            .setIncludePad(false)
            .build()
    }
}
