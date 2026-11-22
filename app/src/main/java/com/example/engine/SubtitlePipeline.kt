package com.example.engine

import android.graphics.Bitmap
import com.example.data.repository.TranslationCacheStore
import com.example.model.DisplayMode
import com.example.model.InPlaceBlock
import com.example.model.SubtitleEntry
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
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
    val isAiRefining: Boolean = false,
    val fromCache: Boolean = false,
    val latencyMs: Long = 0L,
    val framesProcessed: Long = 0L,
    val framesSkipped: Long = 0L,
    val cacheHits: Long = 0L,
    val errorMessage: String? = null,
    val lastCompletedEntry: SubtitleEntry? = null
)

/**
 * Core OCR -> Text Preprocessing -> Self-OCR Guard -> Debounce -> Cache-First / Hybrid / AI Pipeline.
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
    private var lastRenderedTranslations: MutableSet<String> = mutableSetOf()
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
        lastRenderedTranslations.clear()
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
        lastRenderedTranslations.clear()
        lastRenderedCombined = ""
        consecutiveBlankFrames = 0
        boxStabilizer.reset()
        _output.value = _output.value.copy(
            sourceText = "",
            translatedText = "",
            inPlaceBlocks = emptyList(),
            isStabilizing = false,
            isAiRefining = false
        )
    }

    fun isAwaitingBlankClear(): Boolean = consecutiveBlankFrames > 0

    suspend fun processFrame(bitmap: Bitmap, config: TranslationConfig) {
        val current = _output.value

        val bypassDiffer = consecutiveBlankFrames > 0 ||
            current.isStabilizing ||
            pendingCandidate.isNotEmpty()
        if (!bypassDiffer && !frameDiffer.hasVisualChange(bitmap)) {
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
     * Exposed internally for deterministic unit testing of debounce, hybrid flow, self-OCR guard, and caching.
     */
    internal fun processRecognizedResult(
        ocrResult: OcrResult,
        frameBitmap: Bitmap?,
        config: TranslationConfig
    ) {
        val isChinese = config.sourceLangCode == TranslateLanguage.CHINESE
        val normalized = normalizeText(ocrResult.fullText, isChinese)
        val current = _output.value

        val blankClearThreshold = 2
        if (normalized.isBlank()) {
            val hasActiveContent = current.inPlaceBlocks.isNotEmpty() ||
                current.translatedText.isNotBlank() ||
                current.sourceText.isNotBlank() ||
                debounceJob?.isActive == true
            if (!hasActiveContent) {
                consecutiveBlankFrames = 0
                _output.value = current.copy(framesProcessed = current.framesProcessed + 1)
                return
            }
            consecutiveBlankFrames++
            if (consecutiveBlankFrames >= blankClearThreshold) {
                consecutiveBlankFrames = 0
                debounceJob?.cancel()
                debounceJob = null
                pendingCandidate = ""
                pendingBlocks = emptyList()
                recyclePendingSnapshot()
                lastTranslatedSource = ""
                lastRenderedTranslations.clear()
                lastRenderedCombined = ""
                boxStabilizer.reset()
                if (config.displayMode == DisplayMode.IN_PLACE_BLUR && current.inPlaceBlocks.isNotEmpty()) {
                    _output.value = current.copy(
                        sourceText = "",
                        translatedText = "",
                        inPlaceBlocks = emptyList(),
                        isStabilizing = false,
                        isAiRefining = false,
                        framesProcessed = current.framesProcessed + 1
                    )
                } else {
                    _output.value = current.copy(
                        sourceText = "",
                        translatedText = "",
                        isStabilizing = false,
                        isAiRefining = false,
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
            frameDiffer.reset()
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

        val cleanPending = stripMinorPunctuation(pendingCandidate)
        val isPendingMinorFlicker = debounceJob?.isActive == true &&
            pendingCandidate.isNotEmpty() &&
            (
                (cleanCur.isNotEmpty() && cleanCur == cleanPending) ||
                    (cleanPending.length >= 5 &&
                        cleanCur.length >= 5 &&
                        BoundingBoxStabilizer.textSimilarity(cleanCur, cleanPending) >= 0.85f)
                )
        if (isPendingMinorFlicker) {
            _output.value = current.copy(
                framesProcessed = current.framesProcessed + 1,
                framesSkipped = current.framesSkipped + 1
            )
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
            isAiRefining = false,
            framesProcessed = current.framesProcessed + 1,
            errorMessage = null
        )

        debounceJob = scope.launch {
            delay(config.debounceMs)
            if (pendingCandidate == normalized) {
                if (config.engineMode == TranslationEngineMode.HYBRID) {
                    executeHybridTranslation(
                        sourceText = normalized,
                        blocks = pendingBlocks,
                        snapshot = pendingSnapshot,
                        config = config
                    )
                } else {
                    executeTranslation(
                        sourceText = normalized,
                        blocks = pendingBlocks,
                        snapshot = pendingSnapshot,
                        config = config
                    )
                }
            }
        }
    }

    /**
     * Hybrid Zero-Wait Mode:
     * 1. Check SMART_AI cache first -> 0ms instant output if previously refined by AI.
     * 2. Otherwise show OFFLINE_LOCAL translation immediately (<50ms).
     * 3. Refine in background with SMART_AI, cache under SMART_AI, and smoothly update UI + History.
     */
    private suspend fun executeHybridTranslation(
        sourceText: String,
        blocks: List<OcrBlock>,
        snapshot: Bitmap?,
        config: TranslationConfig
    ) {
        val isChinese = config.sourceLangCode == TranslateLanguage.CHINESE
        val startTime = System.currentTimeMillis()
        val offlineProvider = providers[TranslationEngineMode.OFFLINE_LOCAL]
        val aiProvider = providers[TranslationEngineMode.SMART_AI]
        val aiCacheConfig = config.copy(engineMode = TranslationEngineMode.SMART_AI)

        val isMultiBlockInPlace = config.displayMode == DisplayMode.IN_PLACE_BLUR && blocks.size > 1
        val normalizedBlocks = if (isMultiBlockInPlace) {
            blocks.map { normalizeText(it.text, isChinese) }
        } else {
            emptyList()
        }

        try {
            // 1. Check AI Cache First (0ms latency)
            if (isMultiBlockInPlace) {
                val aiCachedList = normalizedBlocks.map { blockNorm ->
                    if (blockNorm.isBlank()) "" else cacheStore.get(
                        engineMode = TranslationEngineMode.SMART_AI,
                        sourceLang = config.sourceLangCode,
                        targetLang = config.targetLangCode,
                        normalizedSource = blockNorm
                    )
                }
                if (aiCachedList.none { it == null }) {
                    val patchBlocks = buildInPlaceBlocks(blocks, normalizedBlocks, aiCachedList.map { it.orEmpty() }, snapshot, config.overlayOpacity)
                    recyclePendingSnapshot()
                    emitHybridSuccess(
                        sourceText = sourceText,
                        translatedText = patchBlocks.joinToString("\n") { it.translatedText }.trim(),
                        inPlaceBlocks = patchBlocks,
                        fromCache = true,
                        isAiRefining = false,
                        latencyMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L),
                        entryEngine = TranslationEngineMode.SMART_AI
                    )
                    return
                }
            } else {
                val exactAiCached = cacheStore.get(
                    engineMode = TranslationEngineMode.SMART_AI,
                    sourceLang = config.sourceLangCode,
                    targetLang = config.targetLangCode,
                    normalizedSource = sourceText
                )
                val cachedAi = exactAiCached ?: findFuzzyCachedTranslation(sourceText, aiCacheConfig)
                if (cachedAi != null) {
                    val singleBlocks = buildSingleInPlaceBlocks(sourceText, cachedAi, blocks, snapshot, config.overlayOpacity, config.displayMode)
                    recyclePendingSnapshot()
                    emitHybridSuccess(
                        sourceText = sourceText,
                        translatedText = cachedAi,
                        inPlaceBlocks = singleBlocks,
                        fromCache = true,
                        isAiRefining = false,
                        latencyMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L),
                        entryEngine = TranslationEngineMode.SMART_AI
                    )
                    return
                }
            }

            // Prepare frosted blur patches once (if IN_PLACE_BLUR) before recycling snapshot
            val baseInPlacePatches = if (config.displayMode == DisplayMode.IN_PLACE_BLUR && blocks.isNotEmpty()) {
                if (isMultiBlockInPlace) {
                    buildInPlaceBlocks(blocks, normalizedBlocks, List(blocks.size) { "" }, snapshot, config.overlayOpacity)
                } else {
                    buildSingleInPlaceBlocks(sourceText, "", blocks, snapshot, config.overlayOpacity, config.displayMode)
                }
            } else {
                emptyList()
            }
            recyclePendingSnapshot()

            // 2. Instant Local Preview (<50ms)
            var showedLocal = false
            var localCombined = ""
            if (offlineProvider != null) {
                val localReady = runCatching { offlineProvider.isReady(config) }.getOrDefault(false)
                if (localReady) {
                    val localResult = runCatching {
                        if (isMultiBlockInPlace) {
                            val localLines = translateBlocksWithCache(
                                provider = offlineProvider,
                                engineMode = TranslationEngineMode.OFFLINE_LOCAL,
                                normalizedBlocks = normalizedBlocks,
                                config = config
                            )
                            val updatedBlocks = baseInPlacePatches.mapIndexed { idx, block ->
                                block.copy(translatedText = localLines.getOrElse(idx) { "" })
                            }
                            updatedBlocks to updatedBlocks.joinToString("\n") { it.translatedText }.trim()
                        } else {
                            val localCached = cacheStore.get(
                                engineMode = TranslationEngineMode.OFFLINE_LOCAL,
                                sourceLang = config.sourceLangCode,
                                targetLang = config.targetLangCode,
                                normalizedSource = sourceText
                            )
                            val translated = localCached ?: offlineProvider.translate(sourceText, config).trim().also { res ->
                                if (res.isNotBlank()) {
                                    cacheStore.put(
                                        engineMode = TranslationEngineMode.OFFLINE_LOCAL,
                                        sourceLang = config.sourceLangCode,
                                        targetLang = config.targetLangCode,
                                        normalizedSource = sourceText,
                                        translated = res
                                    )
                                }
                            }
                            val updatedBlocks = if (baseInPlacePatches.isNotEmpty()) {
                                listOf(baseInPlacePatches.first().copy(translatedText = translated))
                            } else {
                                emptyList()
                            }
                            updatedBlocks to translated
                        }
                    }.getOrNull()

                    if (localResult != null && localResult.second.isNotBlank()) {
                        showedLocal = true
                        localCombined = localResult.second
                        val localLatency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                        emitHybridSuccess(
                            sourceText = sourceText,
                            translatedText = localCombined,
                            inPlaceBlocks = localResult.first,
                            fromCache = false,
                            isAiRefining = aiProvider != null,
                            latencyMs = localLatency,
                            entryEngine = TranslationEngineMode.OFFLINE_LOCAL
                        )
                    }
                }
            }

            // 3. Background AI Refinement
            if (aiProvider == null) {
                if (!showedLocal) {
                    _output.value = _output.value.copy(
                        isStabilizing = false,
                        isAiRefining = false,
                        errorMessage = "Hibrit mod için AI sağlayıcısı bulunamadı."
                    )
                }
                return
            }

            try {
                paceSmartAiIfNeeded(TranslationEngineMode.SMART_AI)
                val aiBlocks: List<InPlaceBlock>
                val aiCombined: String
                if (isMultiBlockInPlace) {
                    val aiLines = translateBlocksWithCache(
                        provider = aiProvider,
                        engineMode = TranslationEngineMode.SMART_AI,
                        normalizedBlocks = normalizedBlocks,
                        config = aiCacheConfig
                    )
                    aiBlocks = baseInPlacePatches.mapIndexed { idx, block ->
                        block.copy(translatedText = aiLines.getOrElse(idx) { "" })
                    }
                    aiCombined = aiBlocks.joinToString("\n") { it.translatedText }.trim()
                } else {
                    aiCombined = aiProvider.translate(sourceText, aiCacheConfig).trim()
                    if (aiCombined.isNotBlank()) {
                        cacheStore.put(
                            engineMode = TranslationEngineMode.SMART_AI,
                            sourceLang = config.sourceLangCode,
                            targetLang = config.targetLangCode,
                            normalizedSource = sourceText,
                            translated = aiCombined
                        )
                        recordFuzzyCache(sourceText, aiCombined, aiCacheConfig)
                    }
                    aiBlocks = if (baseInPlacePatches.isNotEmpty()) {
                        listOf(baseInPlacePatches.first().copy(translatedText = aiCombined))
                    } else {
                        emptyList()
                    }
                }

                if (aiCombined.isNotBlank()) {
                    val totalLatency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    emitHybridSuccess(
                        sourceText = sourceText,
                        translatedText = aiCombined,
                        inPlaceBlocks = aiBlocks,
                        fromCache = false,
                        isAiRefining = false,
                        latencyMs = totalLatency,
                        entryEngine = TranslationEngineMode.SMART_AI
                    )
                } else if (showedLocal) {
                    _output.value = _output.value.copy(isAiRefining = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // If local translation is already visible on screen, keep it visible and clear spinner cleanly
                _output.value = _output.value.copy(
                    isStabilizing = false,
                    isAiRefining = false,
                    errorMessage = if (showedLocal) null else (e.localizedMessage ?: "AI çevirisi başarısız.")
                )
            }
        } catch (e: CancellationException) {
            recyclePendingSnapshot()
            throw e
        } catch (e: Exception) {
            recyclePendingSnapshot()
            _output.value = _output.value.copy(
                isStabilizing = false,
                isAiRefining = false,
                errorMessage = e.localizedMessage ?: "Çeviri başarısız."
            )
        }
    }

    private fun emitHybridSuccess(
        sourceText: String,
        translatedText: String,
        inPlaceBlocks: List<InPlaceBlock>,
        fromCache: Boolean,
        isAiRefining: Boolean,
        latencyMs: Long,
        entryEngine: TranslationEngineMode
    ) {
        lastTranslatedSource = sourceText
        val normCombined = normalizeText(translatedText).lowercase()
        if (normCombined.isNotBlank()) {
            lastRenderedCombined = normCombined
            lastRenderedTranslations.add(normCombined)
        }
        inPlaceBlocks.forEach { block ->
            val normBlock = normalizeText(block.translatedText).lowercase()
            if (normBlock.isNotBlank()) lastRenderedTranslations.add(normBlock)
        }
        pendingCandidate = ""

        val entry = SubtitleEntry(
            sourceText = sourceText,
            translatedText = translatedText,
            fromCache = fromCache,
            latencyMs = latencyMs,
            engineMode = entryEngine
        )
        val prev = _output.value
        _output.value = prev.copy(
            sourceText = sourceText,
            translatedText = translatedText,
            inPlaceBlocks = inPlaceBlocks,
            isStabilizing = false,
            isAiRefining = isAiRefining,
            fromCache = fromCache,
            latencyMs = latencyMs,
            cacheHits = if (fromCache) prev.cacheHits + 1 else prev.cacheHits,
            errorMessage = null,
            lastCompletedEntry = entry
        )
    }

    private suspend fun translateBlocksWithCache(
        provider: TranslationProvider,
        engineMode: TranslationEngineMode,
        normalizedBlocks: List<String>,
        config: TranslationConfig
    ): List<String> {
        val result = MutableList(normalizedBlocks.size) { "" }
        val uncachedIndices = mutableListOf<Int>()
        normalizedBlocks.forEachIndexed { idx, blockNorm ->
            val cached = if (blockNorm.isBlank()) {
                ""
            } else {
                cacheStore.get(
                    engineMode = engineMode,
                    sourceLang = config.sourceLangCode,
                    targetLang = config.targetLangCode,
                    normalizedSource = blockNorm
                )
            }
            if (cached != null) {
                result[idx] = cached
            } else {
                uncachedIndices.add(idx)
            }
        }
        if (uncachedIndices.isNotEmpty()) {
            val toTranslate = uncachedIndices.map { normalizedBlocks[it] }
            val translatedBatch = if (toTranslate.size == 1) {
                listOf(provider.translate(toTranslate.first(), config).trim())
            } else {
                provider.translateBatch(toTranslate, config).map { it.trim() }
            }
            uncachedIndices.forEachIndexed { batchIdx, origIdx ->
                val blockNorm = normalizedBlocks[origIdx]
                val res = translatedBatch.getOrElse(batchIdx) { "" }.trim()
                result[origIdx] = res
                if (res.isNotBlank()) {
                    cacheStore.put(
                        engineMode = engineMode,
                        sourceLang = config.sourceLangCode,
                        targetLang = config.targetLangCode,
                        normalizedSource = blockNorm,
                        translated = res
                    )
                }
            }
        }
        return result
    }

    private fun buildInPlaceBlocks(
        blocks: List<OcrBlock>,
        normalizedBlocks: List<String>,
        translations: List<String>,
        snapshot: Bitmap?,
        opacity: Float
    ): List<InPlaceBlock> {
        return blocks.mapIndexed { idx, block ->
            val patchResult = if (snapshot != null && !snapshot.isRecycled) {
                BlurMaskHelper.createFrostedPatch(snapshot, block.boundingBox, opacity)
            } else {
                BlurredPatchResult(null, 0xDD090E17.toInt())
            }
            InPlaceBlock(
                boundingBox = block.boundingBox,
                sourceText = normalizedBlocks.getOrElse(idx) { block.text },
                translatedText = translations.getOrElse(idx) { "" },
                blurredPatch = patchResult.blurredBitmap,
                sampledBgColor = patchResult.frostedTintColor
            )
        }
    }

    private fun buildSingleInPlaceBlocks(
        sourceText: String,
        translatedText: String,
        blocks: List<OcrBlock>,
        snapshot: Bitmap?,
        opacity: Float,
        displayMode: DisplayMode
    ): List<InPlaceBlock> {
        if (displayMode != DisplayMode.IN_PLACE_BLUR || blocks.isEmpty()) return emptyList()
        val mergedBox = mergeBoundingBoxes(blocks.map { it.boundingBox })
        val patchResult = if (snapshot != null && !snapshot.isRecycled) {
            BlurMaskHelper.createFrostedPatch(snapshot, mergedBox, opacity)
        } else {
            BlurredPatchResult(null, 0xDD090E17.toInt())
        }
        return listOf(
            InPlaceBlock(
                boundingBox = mergedBox,
                sourceText = sourceText,
                translatedText = translatedText,
                blurredPatch = patchResult.blurredBitmap,
                sampledBgColor = patchResult.frostedTintColor
            )
        )
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

                inPlaceResultBlocks = buildSingleInPlaceBlocks(
                    sourceText = sourceText,
                    translatedText = combinedTranslated,
                    blocks = blocks,
                    snapshot = snapshot,
                    opacity = config.overlayOpacity,
                    displayMode = config.displayMode
                )
            }

            recyclePendingSnapshot()
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            lastTranslatedSource = sourceText
            lastRenderedCombined = normalizeText(combinedTranslated).lowercase()
            lastRenderedTranslations = inPlaceResultBlocks
                .map { normalizeText(it.translatedText).lowercase() }
                .filter { it.isNotBlank() }
                .toMutableSet()
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
                isAiRefining = false,
                fromCache = pureCacheHit,
                latencyMs = latency,
                cacheHits = if (pureCacheHit) prev.cacheHits + 1 else prev.cacheHits,
                errorMessage = null,
                lastCompletedEntry = entry
            )
        } catch (e: CancellationException) {
            recyclePendingSnapshot()
            throw e
        } catch (e: Exception) {
            recyclePendingSnapshot()
            _output.value = _output.value.copy(
                isStabilizing = false,
                isAiRefining = false,
                errorMessage = e.localizedMessage ?: "Çeviri başarısız."
            )
        }
    }

    private suspend fun paceSmartAiIfNeeded(engineMode: TranslationEngineMode) {
        if (engineMode != TranslationEngineMode.SMART_AI && engineMode != TranslationEngineMode.HYBRID) return
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
        val lower = normalizedOcr.lowercase().trim()
        if (lower.isEmpty()) return false

        val activeTranslated = _output.value.translatedText.trim().lowercase()
        if (activeTranslated.isNotBlank()) {
            if (lower == activeTranslated) return true
            if (activeTranslated.length >= 5 &&
                BoundingBoxStabilizer.textSimilarity(lower, activeTranslated) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD
            ) {
                return true
            }
        }

        if (lastRenderedCombined.isNotBlank()) {
            if (lower == lastRenderedCombined) return true
            if (lastRenderedCombined.length >= 5 &&
                BoundingBoxStabilizer.textSimilarity(lower, lastRenderedCombined) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD
            ) {
                return true
            }
        }
        for (renderedBlock in lastRenderedTranslations) {
            if (lower == renderedBlock) return true
            if (renderedBlock.length >= 5 &&
                BoundingBoxStabilizer.textSimilarity(lower, renderedBlock) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD
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
        private const val AI_MIN_REQUEST_INTERVAL_MS = 400L
        private const val SELF_TRANSLATION_SIMILARITY_THRESHOLD = 0.85f
        private val MINOR_PUNCT_REGEX = Regex("[.,!?;:'\"`~•·]+")

        fun normalizeText(input: String, isChinese: Boolean = false): String {
            return OcrTextPreprocessor.clean(input, isChinese)
        }
    }
}
