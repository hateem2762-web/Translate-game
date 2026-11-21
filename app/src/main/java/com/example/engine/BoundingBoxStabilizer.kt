package com.example.engine

import com.example.model.RoiRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Eliminates In-Place Blur bounding-box jitter, vibration, and corner-jumping across OCR frames:
 * 1. Deterministically merges OCR fragments on the same visual row.
 * 2. Applies a spatial deadband (ignores sub-threshold pixel wobble).
 * 3. Locks coordinates completely when recognized text is identical or >=85% similar.
 * 4. Smooths genuine multi-frame shifts with an Exponential Moving Average (EMA).
 */
class BoundingBoxStabilizer(
    private val originDeadbandPx: Int = 14,
    private val sizeDeadbandPx: Int = 18,
    private val emaAlpha: Float = 0.25f,
    private val lockSimilarityThreshold: Float = 0.85f
) {
    private data class TrackedBlock(
        val text: String,
        val box: RoiRect
    )

    private var trackedBlocks: List<TrackedBlock> = emptyList()

    fun reset() {
        trackedBlocks = emptyList()
    }

    fun stabilize(rawBlocks: List<OcrBlock>, isChinese: Boolean): List<OcrBlock> {
        if (rawBlocks.isEmpty()) {
            trackedBlocks = emptyList()
            return emptyList()
        }

        val merged = mergeRowBlocks(rawBlocks, isChinese)
        val previous = trackedBlocks

        if (previous.isEmpty() || previous.size != merged.size) {
            trackedBlocks = merged.map { TrackedBlock(it.text, it.boundingBox) }
            return merged
        }

        val stabilized = merged.mapIndexed { index, current ->
            val prev = previous[index]
            val similarity = textSimilarity(prev.text, current.text)
            val prevBox = prev.box
            val curBox = current.boundingBox

            val nextBox = when {
                // Same or nearly identical subtitle text -> freeze exact box coordinates
                similarity >= lockSimilarityThreshold && boxesAreClose(prevBox, curBox, maxDeltaPx = 64) -> {
                    prevBox
                }
                // Within spatial deadband -> keep previous coordinates untouched
                isWithinDeadband(prevBox, curBox) -> {
                    prevBox
                }
                // Close enough to be gradual drift -> apply smooth EMA instead of jumping
                boxesAreClose(prevBox, curBox, maxDeltaPx = 72) -> {
                    smoothBox(prevBox, curBox, emaAlpha)
                }
                // Genuinely moved to another spot on screen -> snap directly
                else -> {
                    curBox
                }
            }

            OcrBlock(text = current.text, boundingBox = nextBox)
        }

        trackedBlocks = stabilized.map { TrackedBlock(it.text, it.boundingBox) }
        return stabilized
    }

    private fun isWithinDeadband(prev: RoiRect, cur: RoiRect): Boolean {
        val prevCx = prev.x + prev.width / 2
        val prevCy = prev.y + prev.height / 2
        val curCx = cur.x + cur.width / 2
        val curCy = cur.y + cur.height / 2

        return abs(prev.x - cur.x) <= originDeadbandPx &&
            abs(prev.y - cur.y) <= originDeadbandPx &&
            abs(prevCx - curCx) <= originDeadbandPx &&
            abs(prevCy - curCy) <= originDeadbandPx &&
            abs(prev.width - cur.width) <= sizeDeadbandPx &&
            abs(prev.height - cur.height) <= sizeDeadbandPx
    }

    private fun boxesAreClose(prev: RoiRect, cur: RoiRect, maxDeltaPx: Int): Boolean {
        val prevCx = prev.x + prev.width / 2
        val prevCy = prev.y + prev.height / 2
        val curCx = cur.x + cur.width / 2
        val curCy = cur.y + cur.height / 2
        return abs(prevCx - curCx) <= maxDeltaPx && abs(prevCy - curCy) <= maxDeltaPx
    }

    private fun smoothBox(prev: RoiRect, cur: RoiRect, alpha: Float): RoiRect {
        val inv = 1f - alpha
        return RoiRect(
            x = (prev.x * inv + cur.x * alpha).toInt(),
            y = (prev.y * inv + cur.y * alpha).toInt(),
            width = (prev.width * inv + cur.width * alpha).toInt().coerceAtLeast(16),
            height = (prev.height * inv + cur.height * alpha).toInt().coerceAtLeast(12)
        )
    }

    companion object {
        /**
         * Groups OCR blocks that share essentially the same horizontal baseline so that a single
         * dialogue line split into two ML Kit blocks merges deterministically left-to-right.
         */
        fun mergeRowBlocks(blocks: List<OcrBlock>, isChinese: Boolean): List<OcrBlock> {
            if (blocks.size <= 1) return blocks

            val sorted = blocks.sortedWith(
                compareBy<OcrBlock> { it.boundingBox.y }.thenBy { it.boundingBox.x }
            )

            val rows = mutableListOf<MutableList<OcrBlock>>()
            for (block in sorted) {
                val centerY = block.boundingBox.y + block.boundingBox.height / 2f
                val targetRow = rows.firstOrNull { row ->
                    val ref = row.first().boundingBox
                    val refCenterY = ref.y + ref.height / 2f
                    val maxH = max(ref.height, block.boundingBox.height).coerceAtLeast(16)
                    abs(centerY - refCenterY) <= maxH * 0.45f
                }
                if (targetRow != null) {
                    targetRow.add(block)
                } else {
                    rows.add(mutableListOf(block))
                }
            }

            return rows.map { row ->
                val ordered = row.sortedBy { it.boundingBox.x }
                if (ordered.size == 1) {
                    ordered.first()
                } else {
                    val minX = ordered.minOf { it.boundingBox.x }
                    val minY = ordered.minOf { it.boundingBox.y }
                    val maxR = ordered.maxOf { it.boundingBox.x + it.boundingBox.width }
                    val maxB = ordered.maxOf { it.boundingBox.y + it.boundingBox.height }
                    val joinedText = if (isChinese) {
                        MlKitOcrEngine.joinChineseBlocks(ordered.map { it.text })
                    } else {
                        ordered.joinToString(" ") { it.text.trim() }.trim()
                    }
                    OcrBlock(
                        text = joinedText,
                        boundingBox = RoiRect(
                            x = minX,
                            y = minY,
                            width = (maxR - minX).coerceAtLeast(16),
                            height = (maxB - minY).coerceAtLeast(12)
                        )
                    )
                }
            }
        }

        /**
         * Normalized Levenshtein similarity in [0.0, 1.0].
         */
        fun textSimilarity(a: String, b: String): Float {
            val s1 = a.trim().lowercase()
            val s2 = b.trim().lowercase()
            if (s1 == s2) return 1f
            val maxLen = max(s1.length, s2.length)
            if (maxLen == 0) return 1f
            val minLen = min(s1.length, s2.length)
            if (minLen == 0) return 0f

            var prev = IntArray(s2.length + 1) { it }
            var curr = IntArray(s2.length + 1)
            for (i in 1..s1.length) {
                curr[0] = i
                val c1 = s1[i - 1]
                for (j in 1..s2.length) {
                    val cost = if (c1 == s2[j - 1]) 0 else 1
                    curr[j] = min(
                        min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            val dist = prev[s2.length]
            return (1f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
        }
    }
}
