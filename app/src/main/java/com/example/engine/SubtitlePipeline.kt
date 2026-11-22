package com.example.engine

import android.graphics.Bitmap
import com.example.data.repository.TranslationCacheStore
import com.example.model.DisplayMode
import com.example.model.InPlaceBlock
import com.example.model.SubtitleEntry
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PipelineOutput(
    val sourceText: String = "",
    val translatedText: String = "",
    val inPlaceBlocks: List<InPlaceBlock> = emptyList(),
    val isStabilizing: Boolean = false,
    val fromCache: Boolean = false,
    val latencyMs: Long = 0L,
    val framesProcessed: Long = 0L,
    val framesSkipped: Long = 0L,
    val cacheHits: Long = 0L,
    val errorMessage: String? = null,
    val lastCompletedEntry: SubtitleEntry? = null
)

/**
 * Core OCR -> Self-OCR Guard -> Typewriter Debounce -> Cache-First (Offline or Smart AI) Pipeline.
 */
class SubtitlePipeline(
    private val scope: CoroutineScope,
    private val ocrEngine: OcrEngine,
    private val providers: Map<TranslationEngineMode, TranslationProvider>,
    private val cacheStore: TranslationCacheStore,
    private val frameDiffer: RoiFrameDiffer = RoiFrameDiffer()
) {
    private val _output = MutableStateFlow(PipelineOutput())
    val output: StateFlow<PipelineOutput> = _output.asStateFlow()

    private var pendingCandidate: String = ""
    private var pendingBlocks: List<OcrBlock> = emptyList()
    private var pendingSnapshot: Bitmap? = null

    private var lastTranslatedSource: String = ""
    private var lastRenderedTranslations: Set<String> = emptySet()
    private var lastRenderedCombined: String = ""
    private var consecutiveBlankFrames: Int = 0
    private var debounceJob: Job? = null
    private val boxStabilizer = BoundingBoxStabilizer()

    // Quota protection: keep a compact fuzzy cache & pace uncached SMART_AI network calls
    private val recentFuzzyCache = ArrayDeque<Pair<String, String>>(25)
    private var lastFuzzyCacheNamespace: String = ""
    private var lastCloudCallElapsedMs: Long = 0L

    fun reset() {
        debounceJob?.cancel()
        debounceJob = null
        pendingCandidate = ""
        pendingBlocks = emptyList()
        recyclePendingSnapshot()
        lastTranslatedSource = ""
        lastRenderedTranslations = emptySet()
        lastRenderedCombined = ""
        consecutiveBlankFrames = 0
        boxStabilizer.reset()
        frameDiffer.reset()
        _output.value = PipelineOutput()
    }

    fun invalidateVisualCache() {
        frameDiffer.reset()
        boxStabilizer.reset()
    }

    fun clearActiveSubtitle() {
        lastTranslatedSource = ""
        lastRenderedTranslations = emptySet()
        lastRenderedCombined = ""
        boxStabilizer.reset()
        _output.value = _output.value.copy(
            translatedText = "",
            inPlaceBlocks = emptyList(),
            isStabilizing = false
        )
    }

    suspend fun processFrame(bitmap: Bitmap, config: TranslationConfig) {
        val current = _output.value

        if (!frameDiffer.hasVisualChange(bitmap)) {
            _output.value = current.copy(framesSkipped = current.framesSkipped + 1)
            return
        }

        val ocrResult = try {
            ocrEngine.recognize(bitmap, config.sourceLangCode)
        } catch (e: Exception) {
            _output.value = _output.value.copy(
                errorMessage = "OCR hatası: ${e.localizedMessage ?: "Bilinmeyen hata"}"
            )
            return
        }

        processRecognizedResult(ocrResult, bitmap, config)
    }

    /**
     * Exposed internally for deterministic unit testing of debounce, self-OCR guard, and caching.
     */
    internal fun processRecognizedResult(
        ocrResult: OcrResult,
        frameBitmap: Bitmap?,
        config: TranslationConfig
    ) {
        val isChinese = config.sourceLangCode == TranslateLanguage.CHINESE
        val normalized = normalizeText(ocrResult.fullText, isChinese)
        val current = _output.value

        val blankClearThreshold = if (config.displayMode == DisplayMode.IN_PLACE_BLUR) 4 else 2
        if (normalized.isBlank()) {
            consecutiveBlankFrames++
            if (consecutiveBlankFrames >= blankClearThreshold) {
                debounceJob?.cancel()
                debounceJob = null
                pendingCandidate = ""
                pendingBlocks = emptyList()
                recyclePendingSnapshot()
                lastTranslatedSource = ""
                lastRenderedTranslations = emptySet()
                lastRenderedCombined = ""
                boxStabilizer.reset()
                if (config.displayMode == DisplayMode.IN_PLACE_BLUR && current.inPlaceBlocks.isNotEmpty()) {
                    _output.value = current.copy(
                        inPlaceBlocks = emptyList(),
                        isStabilizing = false,
                        framesProcessed = current.framesProcessed + 1
                    )
                } else {
                    _output.value = current.copy(
                        isStabilizing = false,
                        framesProcessed = current.framesProcessed + 1
                    )
                }
            } else {
                _output.value = current.copy(framesProcessed = current.framesProcessed + 1)
            }
            return
        }

        consecutiveBlankFrames = 0

        // Self-OCR loop guard: in In-Place mode, never re-translate our own rendered overlay text
        if (config.displayMode == DisplayMode.IN_PLACE_BLUR && isSelfGeneratedOverlayText(normalized)) {
            _output.value = current.copy(
                framesProcessed = current.framesProcessed + 1,
                framesSkipped = current.framesSkipped + 1,
                isStabilizing = false
            )
            return
        }

        // Exact match OR minor OCR/punctuation flicker when text hasn't genuinely changed -> lock everything
        val cleanCur = stripMinorPunctuation(normalized)
        val cleanPrev = stripMinorPunctuation(lastTranslatedSource)
        val isUnchangedOrMinorFlicker = normalized == lastTranslatedSource ||
            (cleanCur.isNotEmpty() && cleanCur == cleanPrev) ||
            (cleanPrev.length >= 5 &&
                cleanCur.length >= 5 &&
                BoundingBoxStabilizer.textSimilarity(cleanCur, cleanPrev) >= 0.82f)
        if (isUnchangedOrMinorFlicker) {
            _output.value = current.copy(
                framesProcessed = current.framesProcessed + 1,
                framesSkipped = current.framesSkipped + 1,
                isStabilizing = false
            )
            return
        }

        if (normalized == pendingCandidate) {
            _output.value = current.copy(framesProcessed = current.framesProcessed + 1)
            return
        }

        pendingCandidate = normalized
        pendingBlocks = boxStabilizer.stabilize(ocrResult.blocks, isChinese)
        recyclePendingSnapshot()
        if (config.displayMode == DisplayMode.IN_PLACE_BLUR && frameBitmap != null && !frameBitmap.isRecycled) {
            pendingSnapshot = runCatching {
                frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
        }

        debounceJob?.cancel()
        _output.value = current.copy(
            sourceText = normalized,
            isStabilizing = true,
            framesProcessed = current.framesProcessed + 1,
            errorMessage = null
        )

        debounceJob = scope.launch {
            delay(config.debounceMs)
            if (pendingCandidate == normalized) {
                executeTranslation(
                    sourceText = normalized,
                    blocks = pendingBlocks,
                    snapshot = pendingSnapshot,
                    config = config
                )
            }
        }
    }

    private suspend fun executeTranslation(
        sourceText: String,
        blocks: List<OcrBlock>,
        snapshot: Bitmap?,
        config: TranslationConfig
    ) {
        val provider = providers[config.engineMode]
            ?: providers[TranslationEngineMode.OFFLINE_LOCAL]
            ?: return

        val isChinese = config.sourceLangCode == TranslateLanguage.CHINESE
        val startTime = System.currentTimeMillis()
        try {
            var anyCacheHit = false
            var anyNetworkOrModelCall = false

            val inPlaceResultBlocks: List<InPlaceBlock>
            val combinedTranslated: String

            if (config.displayMode == DisplayMode.IN_PLACE_BLUR && blocks.size > 1) {
                val normalizedBlocks = blocks.map { normalizeText(it.text, isChinese) }
                val cachedTranslations = arrayOfNulls<String>(blocks.size)
                val uncachedIndices = mutableListOf<Int>()

                normalizedBlocks.forEachIndexed { idx, blockNorm ->
                    val cached = if (blockNorm.isBlank()) {
                        ""
                    } else {
                        cacheStore.get(
                            engineMode = config.engineMode,
                            sourceLang = config.sourceLangCode,
                            targetLang = config.targetLangCode,
                            normalizedSource = blockNorm
                        )
                    }
                    if (cached != null) {
                        if (blockNorm.isNotBlank()) anyCacheHit = true
                        cachedTranslations[idx] = cached
                    } else {
                        uncachedIndices.add(idx)
                    }
                }

                if (uncachedIndices.isNotEmpty()) {
                    anyNetworkOrModelCall = true
                    paceSmartAiIfNeeded(config.engineMode)
                    val toTranslate = uncachedIndices.map { normalizedBlocks[it] }
                    val translatedBatch = if (toTranslate.size == 1) {
                        listOf(provider.translate(toTranslate.first(), config).trim())
                    } else {
                        provider.translateBatch(toTranslate, config).map { it.trim() }
                    }
                    uncachedIndices.forEachIndexed { batchIdx, originalIdx ->
                        val blockNorm = normalizedBlocks[originalIdx]
                        val res = translatedBatch.getOrElse(batchIdx) { "" }.trim()
                        cachedTranslations[originalIdx] = res
                        if (res.isNotBlank()) {
                            cacheStore.put(
                                engineMode = config.engineMode,
                                sourceLang = config.sourceLangCode,
                                targetLang = config.targetLangCode,
                                normalizedSource = blockNorm,
                                translated = res
                            )
                        }
                    }
                }

                val translatedBlocks = blocks.mapIndexed { idx, block ->
                    val blockNorm = normalizedBlocks[idx]
                    val translated = cachedTranslations[idx].orEmpty()
                    val patchResult = if (snapshot != null && !snapshot.isRecycled) {
                        BlurMaskHelper.createFrostedPatch(snapshot, block.boundingBox, config.overlayOpacity)
                    } else {
                        BlurredPatchResult(null, 0xDD090E17.toInt())
                    }
                    InPlaceBlock(
                        boundingBox = block.boundingBox,
                        sourceText = blockNorm,
                        translatedText = translated,
                        blurredPatch = patchResult.blurredBitmap,
                        sampledBgColor = patchResult.frostedTintColor
                    )
                }
                inPlaceResultBlocks = translatedBlocks
                combinedTranslated = translatedBlocks.joinToString("\n") { it.translatedText }.trim()
            } else {
                val exactCached = cacheStore.get(
                    engineMode = config.engineMode,
                    sourceLang = config.sourceLangCode,
                    targetLang = config.targetLangCode,
                    normalizedSource = sourceText
                )
                val cached = exactCached ?: findFuzzyCachedTranslation(sourceText, config)
                if (cached != null) {
                    anyCacheHit = true
                    combinedTranslated = cached
                } else {
                    anyNetworkOrModelCall = true
                    paceSmartAiIfNeeded(config.engineMode)
                    combinedTranslated = provider.translate(sourceText, config).trim()
                    if (combinedTranslated.isNotBlank()) {
                        cacheStore.put(
                            engineMode = config.engineMode,
                            sourceLang = config.sourceLangCode,
                            targetLang = config.targetLangCode,
                            normalizedSource = sourceText,
                            translated = combinedTranslated
                        )
                        recordFuzzyCache(sourceText, combinedTranslated, config)
                    }
                }

                inPlaceResultBlocks = if (config.displayMode == DisplayMode.IN_PLACE_BLUR && blocks.isNotEmpty()) {
                    val mergedBox = mergeBoundingBoxes(blocks.map { it.boundingBox })
                    val patchResult = if (snapshot != null && !snapshot.isRecycled) {
                        BlurMaskHelper.createFrostedPatch(snapshot, mergedBox, config.overlayOpacity)
                    } else {
                        BlurredPatchResult(null, 0xDD090E17.toInt())
                    }
                    listOf(
                        InPlaceBlock(
                            boundingBox = mergedBox,
                            sourceText = sourceText,
                            translatedText = combinedTranslated,
                            blurredPatch = patchResult.blurredBitmap,
                            sampledBgColor = patchResult.frostedTintColor
                        )
                    )
                } else {
                    emptyList()
                }
            }

            recyclePendingSnapshot()
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            lastTranslatedSource = sourceText
            lastRenderedCombined = normalizeText(combinedTranslated).lowercase()
            lastRenderedTranslations = inPlaceResultBlocks
                .map { normalizeText(it.translatedText).lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
            pendingCandidate = ""

            val pureCacheHit = anyCacheHit && !anyNetworkOrModelCall
            val entry = SubtitleEntry(
                sourceText = sourceText,
                translatedText = combinedTranslated,
                fromCache = pureCacheHit,
                latencyMs = latency,
                engineMode = config.engineMode
            )

            val prev = _output.value
            _output.value = prev.copy(
                sourceText = sourceText,
                translatedText = combinedTranslated,
                inPlaceBlocks = inPlaceResultBlocks,
                isStabilizing = false,
                fromCache = pureCacheHit,
                latencyMs = latency,
                cacheHits = if (pureCacheHit) prev.cacheHits + 1 else prev.cacheHits,
                errorMessage = null,
                lastCompletedEntry = entry
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            recyclePendingSnapshot()
            throw e
        } catch (e: Exception) {
            recyclePendingSnapshot()
            _output.value = _output.value.copy(
                isStabilizing = false,
                errorMessage = e.localizedMessage ?: "Çeviri başarısız."
            )
        }
    }

    private suspend fun paceSmartAiIfNeeded(engineMode: TranslationEngineMode) {
        if (engineMode != TranslationEngineMode.SMART_AI) return
        val now = System.currentTimeMillis()
        if (lastCloudCallElapsedMs > 0L) {
            val remaining = AI_MIN_REQUEST_INTERVAL_MS - (now - lastCloudCallElapsedMs)
            if (remaining in 1L..AI_MIN_REQUEST_INTERVAL_MS) {
                delay(remaining)
            }
        }
        lastCloudCallElapsedMs = System.currentTimeMillis()
    }

    private fun isSelfGeneratedOverlayText(normalizedOcr: String): Boolean {
        val lower = normalizedOcr.lowercase()
        if (lastRenderedCombined.isNotBlank() && lower == lastRenderedCombined) return true
        if (lastRenderedTranslations.contains(lower)) return true

        // Fuzzy check if OCR read a partial slice or minor variant of our own rendered overlay
        if (lastRenderedCombined.length >= 6) {
            if (lastRenderedCombined.contains(lower) || lower.contains(lastRenderedCombined)) {
                val ratio = minOf(lower.length, lastRenderedCombined.length).toFloat() /
                    maxOf(lower.length, lastRenderedCombined.length).toFloat()
                if (ratio >= 0.68f) return true
            }
            if (BoundingBoxStabilizer.textSimilarity(lower, lastRenderedCombined) >= 0.72f) {
                return true
            }
        }
        for (renderedBlock in lastRenderedTranslations) {
            if (renderedBlock.length >= 6 &&
                BoundingBoxStabilizer.textSimilarity(lower, renderedBlock) >= 0.72f
            ) {
                return true
            }
        }
        return false
    }

    private fun mergeBoundingBoxes(boxes: List<com.example.model.RoiRect>): com.example.model.RoiRect {
        val minLeft = boxes.minOf { it.x }
        val minTop = boxes.minOf { it.y }
        val maxRight = boxes.maxOf { it.x + it.width }
        val maxBottom = boxes.maxOf { it.y + it.height }
        return com.example.model.RoiRect(
            x = minLeft,
            y = minTop,
            width = (maxRight - minLeft).coerceAtLeast(16),
            height = (maxBottom - minTop).coerceAtLeast(16)
        )
    }

    private fun recyclePendingSnapshot() {
        pendingSnapshot?.let {
            if (!it.isRecycled) it.recycle()
        }
        pendingSnapshot = null
    }

    private fun stripMinorPunctuation(input: String): String {
        return input.replace(MINOR_PUNCT_REGEX, "").trim()
    }

    private fun cacheNamespace(config: TranslationConfig): String {
        return "${config.engineMode.name}:${config.sourceLangCode}->${config.targetLangCode}"
    }

    private fun findFuzzyCachedTranslation(sourceText: String, config: TranslationConfig): String? {
        val ns = cacheNamespace(config)
        if (ns != lastFuzzyCacheNamespace) {
            recentFuzzyCache.clear()
            lastFuzzyCacheNamespace = ns
            return null
        }
        val cleanSource = stripMinorPunctuation(sourceText)
        if (cleanSource.length < 8) return null
        for ((cachedSource, cachedTranslated) in recentFuzzyCache.asReversed()) {
            val cleanCached = stripMinorPunctuation(cachedSource)
            if (cleanCached == cleanSource ||
                (cleanCached.length >= 8 &&
                    BoundingBoxStabilizer.textSimilarity(cleanSource, cleanCached) >= 0.92f)
            ) {
                return cachedTranslated
            }
        }
        return null
    }

    private fun recordFuzzyCache(sourceText: String, translated: String, config: TranslationConfig) {
        val ns = cacheNamespace(config)
        if (ns != lastFuzzyCacheNamespace) {
            recentFuzzyCache.clear()
            lastFuzzyCacheNamespace = ns
        }
        recentFuzzyCache.addLast(sourceText to translated)
        while (recentFuzzyCache.size > 25) {
            recentFuzzyCache.removeFirst()
        }
    }

    companion object {
        private const val AI_MIN_REQUEST_INTERVAL_MS = 2_400L
        private val MULTI_WHITESPACE = Regex("\\s+")
        private val MINOR_PUNCT_REGEX = Regex("[.,!?;:'\"`~•·]+")
        private val CJK_INNER_SPACE = Regex("(?<=[\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])\\s+(?=[\\u4E00-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF])")

        fun normalizeText(input: String, isChinese: Boolean = false): String {
            val collapsed = input.replace(MULTI_WHITESPACE, " ").trim()
            return if (isChinese) collapsed.replace(CJK_INNER_SPACE, "") else collapsed
        }
    }
}
