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
    val resultEngineMode: TranslationEngineMode? = null,
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
    private var lastTranslatedText: String = ""
    private var lastRenderedTranslations: MutableSet<String> = mutableSetOf()
    private var lastRenderedCombined: String = ""
    private var consecutiveBlankFrames: Int = 0
    private var debounceJob: Job? = null
    private val boxStabilizer = BoundingBoxStabilizer()

    // Quota protection: keep a compact fuzzy cache & pace uncached SMART_AI network calls
    private var recentFuzzyCache = ArrayDeque<Pair<String, String>>(25)
    private var lastFuzzyCacheNamespace: String = ""
    private var lastCloudCallElapsedMs: Long = 0L
    private var stealthCooldownUntilMs: Long = 0L
    private var consecutiveSameTextPeeks: Int = 0

    fun reset() {
        debounceJob?.cancel()
        debounceJob = null
        pendingCandidate = ""
        pendingBlocks = emptyList()
        recyclePendingSnapshot()
        lastTranslatedSource = ""
        lastTranslatedText = ""
        lastRenderedTranslations.clear()
        lastRenderedCombined = ""
        consecutiveBlankFrames = 0
        consecutiveSameTextPeeks = 0
        stealthCooldownUntilMs = 0L
        boxStabilizer.reset()
        frameDiffer.reset()
        _output.value = PipelineOutput()
    }

    fun invalidateVisualCache() {
        frameDiffer.reset()
        boxStabilizer.reset()
        consecutiveSameTextPeeks = 0
        stealthCooldownUntilMs = 0L
    }

    fun clearActiveSubtitle() {
        lastTranslatedSource = ""
        lastTranslatedText = ""
        lastRenderedTranslations.clear()
        lastRenderedCombined = ""
        consecutiveBlankFrames = 0
        consecutiveSameTextPeeks = 0
        stealthCooldownUntilMs = 0L
        boxStabilizer.reset()
        _output.value = _output.value.copy(
            sourceText = "",
            translatedText = "",
            inPlaceBlocks = emptyList(),
            isStabilizing = false,
            isAiRefining = false,
            lastCompletedEntry = null
        )
    }

    fun isAwaitingBlankClear(): Boolean = consecutiveBlankFrames > 0

    fun isStabilizingOrPending(): Boolean =
        _output.value.isStabilizing || pendingCandidate.isNotEmpty() || debounceJob?.isActive == true

    fun canStealthPeekNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= stealthCooldownUntilMs

    fun hasUnmaskedVisualChange(
        bitmap: Bitmap,
        maskedRects: List<com.example.model.RoiRect>,
        maskSignature: Int
    ): Boolean = frameDiffer.hasUnmaskedVisualChange(bitmap, maskedRects, maskSignature)

    fun recordPassiveSkip() {
        _output.value = _output.value.copy(framesSkipped = _output.value.framesSkipped + 1)
    }

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

        val blankClearThreshold = if (config.displayMode == DisplayMode.IN_PLACE_BLUR) 3 else 2
        if (normalized.isBlank()) {
            val hasActiveContent = current.inPlaceBlocks.isNotEmpty() ||
                current.translatedText.isNotBlank() ||
                current.sourceText.isNotBlank() ||
                debounceJob?.isActive == true
            if (!hasActiveContent) {
                consecutiveBlankFrames = 0
                _output.value = current.copy(
                    framesProcessed = current.framesProcessed + 1,
                    lastCompletedEntry = null
                )
                return
            }
            consecutiveBlankFrames++
            if (consecutiveBlankFrames >= blankClearThreshold) {
                consecutiveBlankFrames = 0
                consecutiveSameTextPeeks = 0
                stealthCooldownUntilMs = 0L
                debounceJob?.cancel()
                debounceJob = null
                pendingCandidate = ""
                pendingBlocks = emptyList()
                recyclePendingSnapshot()
                lastTranslatedSource = ""
                lastTranslatedText = ""
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
                        framesProcessed = current.framesProcessed + 1,
                        lastCompletedEntry = null
                    )
                } else {
                    _output.value = current.copy(
                        sourceText = "",
                        translatedText = "",
                        isStabilizing = false,
                        isAiRefining = false,
                        framesProcessed = current.framesProcessed + 1,
                        lastCompletedEntry = null
                    )
                }
            } else {
                _output.value = current.copy(
                    framesProcessed = current.framesProcessed + 1,
                    lastCompletedEntry = null
                )
            }
            return
        }

        consecutiveBlankFrames = 0

        // Layer B Self-OCR Loop & History Guard: in In-Place Blur mode, reject rendered overlay text
        // (>= 80% similarity to lastTranslatedText) or already-target-language Turkish echoes,
        // keep current subtitle fixed on screen, and never trigger translation or history entry.
        if (config.displayMode == DisplayMode.IN_PLACE_BLUR && isSelfGeneratedOverlayText(normalized, config)) {
            debounceJob?.cancel()
            debounceJob = null
            pendingCandidate = ""
            pendingBlocks = emptyList()
            recyclePendingSnapshot()
            consecutiveSameTextPeeks = (consecutiveSameTextPeeks + 1).coerceAtMost(2)
            stealthCooldownUntilMs = System.currentTimeMillis() + 1200L
            frameDiffer.reset()
            _output.value = current.copy(
                framesProcessed = current.framesProcessed + 1,
                framesSkipped = current.framesSkipped + 1,
                isStabilizing = false,
                lastCompletedEntry = null
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
            consecutiveSameTextPeeks = (consecutiveSameTextPeeks + 1).coerceAtMost(2)
            stealthCooldownUntilMs = System.currentTimeMillis() + (900L * consecutiveSameTextPeeks)
            _output.value = current.copy(
                framesProcessed = current.framesProcessed + 1,
                framesSkipped = current.framesSkipped + 1,
                isStabilizing = false,
                lastCompletedEntry = null
            )
            return
        }

        if (normalized == pendingCandidate) {
            _output.value = current.copy(
                framesProcessed = current.framesProcessed + 1,
                lastCompletedEntry = null
            )
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
                framesSkipped = current.framesSkipped + 1,
                lastCompletedEntry = null
            )
            return
        }

        consecutiveSameTextPeeks = 0
        stealthCooldownUntilMs = 0L
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
            errorMessage = null,
            lastCompletedEntry = null
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
                    val patchBlocks = buildInPlaceBlocks(blocks, normalizedBlocks, aiCachedList.map { it.orEmpty() }, snapshot, config)
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
                    val singleBlocks = buildSingleInPlaceBlocks(sourceText, cachedAi, blocks, snapshot, config)
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
                    buildInPlaceBlocks(blocks, normalizedBlocks, List(blocks.size) { "" }, snapshot, config)
                } else {
                    buildSingleInPlaceBlocks(sourceText, "", blocks, snapshot, config)
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
                    _output.value = _output.value.copy(isAiRefining = false, lastCompletedEntry = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // If local translation is already visible on screen, keep it visible and clear spinner cleanly
                _output.value = _output.value.copy(
                    isStabilizing = false,
                    isAiRefining = false,
                    errorMessage = if (showedLocal) null else (e.localizedMessage ?: "AI çevirisi başarısız."),
                    lastCompletedEntry = null
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
                errorMessage = e.localizedMessage ?: "Çeviri başarısız.",
                lastCompletedEntry = null
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
        lastTranslatedText = translatedText.trim()
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
            resultEngineMode = entryEngine,
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
        config: TranslationConfig
    ): List<InPlaceBlock> {
        return blocks.mapIndexed { idx, block ->
            val patchResult = if (snapshot != null && !snapshot.isRecycled) {
                BlurMaskHelper.createPatch(
                    sourceBitmap = snapshot,
                    box = block.boundingBox,
                    opacity = config.overlayOpacity,
                    blurStyle = config.inPlaceBlurStyle,
                    blurIntensity = config.inPlaceBlurIntensity
                )
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
        config: TranslationConfig
    ): List<InPlaceBlock> {
        if (config.displayMode != DisplayMode.IN_PLACE_BLUR || blocks.isEmpty()) return emptyList()
        val mergedBox = mergeBoundingBoxes(blocks.map { it.boundingBox })
        val patchResult = if (snapshot != null && !snapshot.isRecycled) {
            BlurMaskHelper.createPatch(
                sourceBitmap = snapshot,
                box = mergedBox,
                opacity = config.overlayOpacity,
                blurStyle = config.inPlaceBlurStyle,
                blurIntensity = config.inPlaceBlurIntensity
            )
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
        if (provider == null) {
            recyclePendingSnapshot()
            _output.value = _output.value.copy(
                translatedText = "",
                inPlaceBlocks = emptyList(),
                isStabilizing = false,
                isAiRefining = false,
                resultEngineMode = config.engineMode,
                errorMessage = "${config.engineMode.fullTitleTr} motoru hazır değil."
            )
            return
        }

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
                        BlurMaskHelper.createPatch(
                            sourceBitmap = snapshot,
                            box = block.boundingBox,
                            opacity = config.overlayOpacity,
                            blurStyle = config.inPlaceBlurStyle,
                            blurIntensity = config.inPlaceBlurIntensity
                        )
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
                    config = config
                )
            }

            recyclePendingSnapshot()
            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            lastTranslatedSource = sourceText
            lastTranslatedText = combinedTranslated.trim()
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
                resultEngineMode = config.engineMode,
                lastCompletedEntry = entry
            )
        } catch (e: CancellationException) {
            recyclePendingSnapshot()
            throw e
        } catch (e: Exception) {
            recyclePendingSnapshot()
            _output.value = _output.value.copy(
                translatedText = "",
                inPlaceBlocks = emptyList(),
                isStabilizing = false,
                isAiRefining = false,
                resultEngineMode = config.engineMode,
                errorMessage = e.localizedMessage ?: "Çeviri başarısız.",
                lastCompletedEntry = null
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

    private fun isSelfGeneratedOverlayText(
        normalizedOcr: String,
        config: TranslationConfig
    ): Boolean {
        val lower = normalizedOcr.lowercase().trim()
        if (lower.isEmpty()) return false
        val strippedOcr = stripMinorPunctuation(lower)
        val foldedOcr = foldTurkishForMatch(strippedOcr)

        val candidates = LinkedHashSet<String>()
        if (lastTranslatedText.isNotBlank()) {
            candidates.add(normalizeText(lastTranslatedText).lowercase())
            lastTranslatedText.lineSequence()
                .map { normalizeText(it).lowercase() }
                .filter { it.isNotBlank() }
                .forEach { candidates.add(it) }
        }
        val activeTranslated = _output.value.translatedText.trim().lowercase()
        if (activeTranslated.isNotBlank()) {
            candidates.add(activeTranslated)
        }
        if (lastRenderedCombined.isNotBlank()) {
            candidates.add(lastRenderedCombined)
        }
        candidates.addAll(lastRenderedTranslations)

        val ocrTokens = tokenizeForOverlap(foldedOcr)

        for (candidate in candidates) {
            if (lower == candidate) return true
            val strippedCandidate = stripMinorPunctuation(candidate)
            if (strippedOcr.isNotEmpty() && strippedOcr == strippedCandidate) return true

            val foldedCandidate = foldTurkishForMatch(strippedCandidate)
            if (foldedOcr.isNotEmpty() && foldedOcr == foldedCandidate) return true

            if (candidate.length >= 4 &&
                BoundingBoxStabilizer.textSimilarity(lower, candidate) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD
            ) {
                return true
            }
            if (strippedCandidate.length >= 4 &&
                strippedOcr.length >= 4 &&
                BoundingBoxStabilizer.textSimilarity(strippedOcr, strippedCandidate) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD
            ) {
                return true
            }
            if (foldedCandidate.length >= 4 &&
                foldedOcr.length >= 4 &&
                BoundingBoxStabilizer.textSimilarity(foldedOcr, foldedCandidate) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD
            ) {
                return true
            }
            if (foldedCandidate.length >= 8 &&
                foldedOcr.length >= 6 &&
                (foldedCandidate.contains(foldedOcr) || foldedOcr.contains(foldedCandidate))
            ) {
                return true
            }

            if (ocrTokens.size >= 2) {
                val candTokens = tokenizeForOverlap(foldedCandidate)
                if (candTokens.isNotEmpty()) {
                    val matchedTokens = ocrTokens.count { ot ->
                        candTokens.any { ct ->
                            ot == ct ||
                                (ot.length >= 4 && ct.length >= 4 &&
                                    BoundingBoxStabilizer.textSimilarity(ot, ct) >= SELF_TRANSLATION_SIMILARITY_THRESHOLD)
                        }
                    }
                    if (matchedTokens * 1.0f / ocrTokens.size >= 0.60f) {
                        return true
                    }
                }
            }
        }

        if (config.targetLangCode == TranslateLanguage.TURKISH &&
            config.sourceLangCode != TranslateLanguage.TURKISH &&
            isLikelyTurkishText(normalizedOcr, config.sourceLangCode)
        ) {
            return true
        }
        return false
    }

    private fun foldTurkishForMatch(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                'ğ', 'Ğ' -> sb.append('g')
                'ü', 'Ü' -> sb.append('u')
                'ş', 'Ş' -> sb.append('s')
                'ı', 'İ' -> sb.append('i')
                'ö', 'Ö' -> sb.append('o')
                'ç', 'Ç' -> sb.append('c')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    private fun tokenizeForOverlap(foldedInput: String): List<String> {
        if (foldedInput.isBlank()) return emptyList()
        return foldedInput.split(TOKEN_SPLIT_REGEX)
            .filter { it.length >= 3 }
    }

    private fun isLikelyTurkishText(text: String, sourceLangCode: String): Boolean {
        var uniqueTurkishChars = 0
        var totalTurkishDiacritics = 0
        for (ch in text) {
            when (ch) {
                'ğ', 'Ğ', 'ş', 'Ş', 'ı', 'İ' -> {
                    uniqueTurkishChars++
                    totalTurkishDiacritics++
                }
                'ç', 'Ç', 'ö', 'Ö', 'ü', 'Ü' -> totalTurkishDiacritics++
            }
        }
        if (uniqueTurkishChars >= 1 && (lastTranslatedText.isNotBlank() || text.length >= 6)) {
            return true
        }
        if ((sourceLangCode == TranslateLanguage.ENGLISH || sourceLangCode == TranslateLanguage.CHINESE) &&
            totalTurkishDiacritics >= 2
        ) {
            return true
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
        internal const val SELF_TRANSLATION_SIMILARITY_THRESHOLD = 0.80f
        private val MINOR_PUNCT_REGEX = Regex("[.,!?;:'\"`~•·]+")
        private val TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{Nd}]+")

        fun normalizeText(input: String, isChinese: Boolean = false): String {
            return OcrTextPreprocessor.clean(input, isChinese)
        }
    }
}
