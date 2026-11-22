package com.example.engine

import com.example.model.RoiRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Eliminates In-Place Blur bounding-box jitter, vibration, corner-jumping, and fragmented
 * multi-line splitting across OCR frames:
 * 1. Deterministically merges OCR fragments on the same visual row (respecting horizontal gaps).
 * 2. Merges tightly stacked multi-line dialogue sentences into ONE cohesive paragraph block so
 *    translations stay grammatically natural and visually unified.
 * 3. Applies a spatial deadband (ignores sub-threshold pixel wobble).
 * 4. Locks coordinates completely when recognized text is identical or >=85% similar.
 * 5. Smooths genuine multi-frame shifts with an Exponential Moving Average (EMA).
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

        val rowMerged = mergeRowBlocks(rawBlocks, isChinese)
        val merged = mergeParagraphBlocks(rowMerged, isChinese)
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
         * Groups OCR blocks that share the same horizontal baseline AND sit within normal
         * phrase spacing (leaving distant left/right UI buttons separate).
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

            val merged = mutableListOf<OcrBlock>()
            for (row in rows) {
                val ordered = row.sortedBy { it.boundingBox.x }
                if (ordered.size == 1) {
                    merged.add(ordered.first())
                    continue
                }
                val clusters = mutableListOf<MutableList<OcrBlock>>()
                for (blk in ordered) {
                    val lastCluster = clusters.lastOrNull()
                    if (lastCluster == null) {
                        clusters.add(mutableListOf(blk))
                    } else {
                        val prevRight = lastCluster.maxOf { it.boundingBox.x + it.boundingBox.width }
                        val maxH = max(lastCluster.maxOf { it.boundingBox.height }, blk.boundingBox.height).coerceAtLeast(16)
                        val horizontalGap = blk.boundingBox.x - prevRight
                        if (horizontalGap <= maxH * 2.5f) {
                            lastCluster.add(blk)
                        } else {
                            clusters.add(mutableListOf(blk))
                        }
                    }
                }
                for (cluster in clusters) {
                    merged.add(combineCluster(cluster, isChinese, isParagraphVerticalMerge = false))
                }
            }
            return merged
        }

        /**
         * Merges consecutive vertical dialogue lines that form ONE coherent paragraph/sentence
         * (tight line-leading gap, compatible font height, overlapping/centered horizontal column)
         * so translations stay fluent and draw as ONE unified frosted panel rather than jagged
         * line-by-line fragments. Distant regions or side-by-side buttons remain separate.
         */
        fun mergeParagraphBlocks(blocks: List<OcrBlock>, isChinese: Boolean): List<OcrBlock> {
            if (blocks.size <= 1) return blocks

            val sorted = blocks.sortedWith(
                compareBy<OcrBlock> { it.boundingBox.y }.thenBy { it.boundingBox.x }
            )

            val paragraphs = mutableListOf<MutableList<OcrBlock>>()
            for (blk in sorted) {
                val targetPara = paragraphs.lastOrNull()?.takeIf { para ->
                    val last = para.last()
                    shouldMergeIntoSameParagraph(last.boundingBox, blk.boundingBox)
                }
                if (targetPara != null) {
                    targetPara.add(blk)
                } else {
                    paragraphs.add(mutableListOf(blk))
                }
            }

            return paragraphs.map { para ->
                combineCluster(para, isChinese, isParagraphVerticalMerge = true)
            }
        }

        private fun shouldMergeIntoSameParagraph(topBox: RoiRect, bottomBox: RoiRect): Boolean {
            val topBottom = topBox.y + topBox.height
            val gapY = bottomBox.y - topBottom
            val minH = min(topBox.height, bottomBox.height).coerceAtLeast(12)
            val maxH = max(topBox.height, bottomBox.height).coerceAtLeast(12)

            // Same-row side-by-side elements (negative vertical gap exceeding line height) shouldn't merge here
            if (gapY < -minH * 0.45f) return false
            // Separate UI sections (e.g. header vs subtitle far below) have large vertical gap
            if (gapY > maxH * 0.95f) return false
            // Significantly different font sizes (e.g. tiny tag vs big dialogue) stay separate
            if (maxH > minH * 1.85f) return false

            val topRight = topBox.x + topBox.width
            val bottomRight = bottomBox.x + bottomBox.width
            val horizOverlap = min(topRight, bottomRight) - max(topBox.x, bottomBox.x)
            val topCx = topBox.x + topBox.width / 2f
            val bottomCx = bottomBox.x + bottomBox.width / 2f
            val maxW = max(topBox.width, bottomBox.width).coerceAtLeast(24)

            return horizOverlap > -maxH * 1.2f || abs(topCx - bottomCx) <= maxW * 0.65f
        }

        private fun combineCluster(
            cluster: List<OcrBlock>,
            isChinese: Boolean,
            isParagraphVerticalMerge: Boolean
        ): OcrBlock {
            if (cluster.size == 1) return cluster.first()
            val minX = cluster.minOf { it.boundingBox.x }
            val minY = cluster.minOf { it.boundingBox.y }
            val maxR = cluster.maxOf { it.boundingBox.x + it.boundingBox.width }
            val maxB = cluster.maxOf { it.boundingBox.y + it.boundingBox.height }
            val joinedText = when {
                isChinese -> OcrTextPreprocessor.joinChineseBlocks(cluster.map { it.text })
                isParagraphVerticalMerge -> OcrTextPreprocessor.joinParagraphLines(cluster.map { it.text })
                else -> cluster.joinToString(" ") { it.text.trim() }.trim()
            }
            return OcrBlock(
                text = joinedText,
                boundingBox = RoiRect(
                    x = minX,
                    y = minY,
                    width = (maxR - minX).coerceAtLeast(16),
                    height = (maxB - minY).coerceAtLeast(12)
                )
            )
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
