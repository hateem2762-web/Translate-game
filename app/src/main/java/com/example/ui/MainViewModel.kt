package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.ScreenMetricsHelper
import com.example.data.local.SubLensDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.TranslationCacheRepository
import com.example.engine.BlurMaskHelper
import com.example.engine.BlurredPatchResult
import com.example.engine.BoundingBoxStabilizer
import com.example.engine.MlKitOcrEngine
import com.example.engine.MlKitTranslatorEngine
import com.example.engine.OfflineMlKitTranslationProvider
import com.example.engine.SmartAiTranslationProvider
import com.example.engine.SubtitlePipeline
import com.example.model.AiModelOption
import com.example.model.AiProviderPreset
import com.example.model.ClassicTextFitMode
import com.example.model.DisplayMode
import com.example.model.InPlaceBlock
import com.example.model.ModelDownloadState
import com.example.model.RoiRect
import com.example.model.SupportedLanguage
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.example.model.TranslatorTelemetry
import com.example.service.ScreenTranslatorService
import com.example.service.ServiceStateHolder
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data class Success(val sampleOutput: String) : AiTestState
    data class Error(val message: String, val isQuotaError: Boolean = false) : AiTestState
}

enum class SandboxPreset(
    val labelTr: String,
    val sourceLangCode: String,
    val sampleText: String
) {
    ENGLISH_RPG(
        labelTr = "İngilizce",
        sourceLangCode = TranslateLanguage.ENGLISH,
        sampleText = "The ancient seal is breaking! Find the key before the shadow consumes the village."
    ),
    CHINESE_RPG(
        labelTr = "Çince",
        sourceLangCode = TranslateLanguage.CHINESE,
        sampleText = "古老的封印正在碎裂！在黑暗吞噬村庄之前，快找到钥匙。"
    ),
    MULTI_LINE_RPG(
        labelTr = "3 Satır",
        sourceLangCode = TranslateLanguage.ENGLISH,
        sampleText = "The ancient seal is breaking apart!\nFind the golden key in the ruins\nbefore darkness consumes this village."
    )
}

data class SandboxState(
    val selectedPreset: SandboxPreset = SandboxPreset.ENGLISH_RPG,
    val isRunning: Boolean = false,
    val isAiRefining: Boolean = false,
    val recognizedText: String = "",
    val translatedText: String = "",
    val inPlaceBlocks: List<InPlaceBlock> = emptyList(),
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

data class ModelCatalogState(
    val modelsByPreset: Map<AiProviderPreset, List<AiModelOption>> = emptyMap(),
    val isFetching: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

data class MainUiState(
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val config: TranslationConfig = TranslationConfig(),
    val roiRect: RoiRect = RoiRect(100, 1200, 880, 240),
    val modelState: ModelDownloadState = ModelDownloadState.UNKNOWN,
    val modelError: String? = null,
    val cacheCount: Int = 0,
    val telemetry: TranslatorTelemetry = TranslatorTelemetry(),
    val aiTestState: AiTestState = AiTestState.Idle,
    val sandboxState: SandboxState = SandboxState(),
    val modelCatalogState: ModelCatalogState = ModelCatalogState()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsStore = SettingsDataStore(appContext)
    private val cacheRepository = TranslationCacheRepository(
        SubLensDatabase.getInstance(appContext).translationCacheDao()
    )
    private val translatorEngine = MlKitTranslatorEngine()
    private val offlineTranslator = OfflineMlKitTranslationProvider(translatorEngine)
    private val smartAiTester = SmartAiTranslationProvider(appContext)
    private val sandboxOcrEngine = MlKitOcrEngine()

    private val screenBounds = ScreenMetricsHelper.getScreenBounds(appContext)

    private val permissionState = MutableStateFlow(readPermissions())
    private val modelStatusState = MutableStateFlow(ModelDownloadState.UNKNOWN to (null as String?))
    private val aiTestStatusState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    private val sandboxStatusState = MutableStateFlow(SandboxState())
    private val modelCatalogStatusState = MutableStateFlow(ModelCatalogState())

    // Per-provider isolated draft maps so switching providers never leaks another provider's key
    private val activePresetDraft = MutableStateFlow(AiProviderPreset.GROQ)
    private val apiKeyDrafts = mutableMapOf<AiProviderPreset, String>()
    private val modelDrafts = mutableMapOf<AiProviderPreset, String>()
    private val endpointDrafts = mutableMapOf<AiProviderPreset, String>()

    // Synchronous text-field drafts backed immediately by DataStore so typing/pasting never drops
    private val _aiApiKeyDraft = MutableStateFlow("")
    val aiApiKeyDraft: StateFlow<String> = _aiApiKeyDraft.asStateFlow()

    private val _aiModelDraft = MutableStateFlow(AiProviderPreset.GROQ.defaultModel)
    val aiModelDraft: StateFlow<String> = _aiModelDraft.asStateFlow()

    private val _aiEndpointDraft = MutableStateFlow(AiProviderPreset.GROQ.defaultBaseUrl)
    val aiEndpointDraft: StateFlow<String> = _aiEndpointDraft.asStateFlow()

    val uiState: StateFlow<MainUiState> = combine(
        permissionState,
        settingsStore.configFlow,
        settingsStore.observeRoi(screenBounds.width, screenBounds.height),
        modelStatusState,
        cacheRepository.observeCacheCount(),
        ServiceStateHolder.telemetry,
        aiTestStatusState,
        sandboxStatusState,
        modelCatalogStatusState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val perms = values[0] as Pair<Boolean, Boolean>
        val config = values[1] as TranslationConfig
        val roi = values[2] as RoiRect
        @Suppress("UNCHECKED_CAST")
        val modelPair = values[3] as Pair<ModelDownloadState, String?>
        val cacheCount = values[4] as Int
        val telemetry = values[5] as TranslatorTelemetry
        val aiTestState = values[6] as AiTestState
        val sandboxState = values[7] as SandboxState
        val catalogState = values[8] as ModelCatalogState

        MainUiState(
            hasOverlayPermission = perms.first,
            hasNotificationPermission = perms.second,
            config = config,
            roiRect = roi,
            modelState = modelPair.first,
            modelError = modelPair.second,
            cacheCount = cacheCount,
            telemetry = telemetry,
            aiTestState = aiTestState,
            sandboxState = sandboxState,
            modelCatalogState = catalogState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(
            hasOverlayPermission = permissionState.value.first,
            hasNotificationPermission = permissionState.value.second,
            roiRect = RoiRect.defaultForScreen(screenBounds.width, screenBounds.height)
        )
    )

    init {
        viewModelScope.launch {
            val initialConfig = settingsStore.configFlow.first()
            AiProviderPreset.entries.forEach { preset ->
                apiKeyDrafts[preset] = initialConfig.apiKeyFor(preset)
                modelDrafts[preset] = initialConfig.modelFor(preset)
                endpointDrafts[preset] = initialConfig.endpointFor(preset)
            }
            activePresetDraft.value = initialConfig.aiPreset

            _aiApiKeyDraft.value = initialConfig.apiKeyFor(initialConfig.aiPreset)
            _aiModelDraft.value = initialConfig.aiModelName
            _aiEndpointDraft.value = initialConfig.aiEndpointUrl

            settingsStore.configFlow.collect { config ->
                checkModelAvailability(config.sourceLangCode, config.targetLangCode)
            }
        }
    }

    fun refreshPermissions() {
        permissionState.value = readPermissions()
    }

    private fun readPermissions(): Pair<Boolean, Boolean> {
        val overlay = Settings.canDrawOverlays(appContext)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return overlay to notifications
    }

    fun checkModelAvailability(sourceLang: String, targetLang: String) {
        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    translatorEngine.isModelReady(sourceLang, targetLang)
                }
                modelStatusState.value = if (ready) {
                    ModelDownloadState.READY to null
                } else {
                    ModelDownloadState.NOT_DOWNLOADED to null
                }
            } catch (e: Exception) {
                modelStatusState.value = ModelDownloadState.ERROR to e.localizedMessage
            }
        }
    }

    fun downloadLanguageModels() {
        val config = uiState.value.config
        if (modelStatusState.value.first == ModelDownloadState.DOWNLOADING) return
        viewModelScope.launch {
            modelStatusState.value = ModelDownloadState.DOWNLOADING to null
            try {
                withContext(Dispatchers.IO) {
                    translatorEngine.downloadModelsIfNeeded(
                        sourceLang = config.sourceLangCode,
                        targetLang = config.targetLangCode,
                        requireWifi = false
                    )
                }
                modelStatusState.value = ModelDownloadState.READY to null
            } catch (e: Exception) {
                modelStatusState.value = ModelDownloadState.ERROR to
                    (e.localizedMessage ?: "Dil paketi indirilemedi.")
            }
        }
    }

    fun updateDisplayMode(mode: DisplayMode) {
        viewModelScope.launch { settingsStore.updateDisplayMode(mode) }
    }

    fun updateEngineMode(mode: TranslationEngineMode) {
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateEngineMode(mode) }
    }

    fun updateFontSizeSp(fontSizeSp: Float) {
        viewModelScope.launch { settingsStore.updateFontSizeSp(fontSizeSp) }
    }

    fun updateSubtitleTextColorArgb(argb: Int) {
        viewModelScope.launch { settingsStore.updateSubtitleTextColorArgb(argb) }
    }

    fun updateClassicTextFitMode(mode: ClassicTextFitMode) {
        viewModelScope.launch { settingsStore.updateClassicTextFitMode(mode) }
    }

    fun updateInPlacePaddingX(dp: Float) {
        viewModelScope.launch { settingsStore.updateInPlacePaddingX(dp) }
    }

    fun updateInPlacePaddingY(dp: Float) {
        viewModelScope.launch { settingsStore.updateInPlacePaddingY(dp) }
    }

    fun updateInPlaceCornerRadius(dp: Float) {
        viewModelScope.launch { settingsStore.updateInPlaceCornerRadius(dp) }
    }

    fun updateInPlaceOffsetX(dp: Float) {
        viewModelScope.launch { settingsStore.updateInPlaceOffsetX(dp) }
    }

    fun updateInPlaceOffsetY(dp: Float) {
        viewModelScope.launch { settingsStore.updateInPlaceOffsetY(dp) }
    }

    fun resetInPlaceTuning() {
        viewModelScope.launch { settingsStore.resetInPlaceTuning() }
    }

    fun updateClassicBoxWidthDp(widthDp: Int) {
        viewModelScope.launch { settingsStore.updateClassicBoxWidthDp(widthDp) }
    }

    fun updateClassicBoxHeightDp(heightDp: Int) {
        viewModelScope.launch { settingsStore.updateClassicBoxHeightDp(heightDp) }
    }

    fun updateClassicBoxOpacity(opacity: Float) {
        viewModelScope.launch { settingsStore.updateClassicBoxOpacity(opacity) }
    }

    fun resetClassicBoxLayout() {
        viewModelScope.launch { settingsStore.resetClassicBoxLayout() }
    }

    fun clearSessionHistory() {
        ServiceStateHolder.clearHistory()
    }

    fun updateAiPreset(preset: AiProviderPreset) {
        activePresetDraft.value = preset
        val isolatedKey = apiKeyDrafts[preset] ?: uiState.value.config.apiKeyFor(preset)
        val isolatedModel = AiProviderPreset.sanitizeModelId(
            preset,
            modelDrafts[preset] ?: uiState.value.config.modelFor(preset)
        )
        val isolatedEndpoint = endpointDrafts[preset]?.takeIf { it.isNotBlank() }
            ?: uiState.value.config.endpointFor(preset)

        _aiApiKeyDraft.value = isolatedKey
        _aiModelDraft.value = isolatedModel
        _aiEndpointDraft.value = isolatedEndpoint
        aiTestStatusState.value = AiTestState.Idle
        modelCatalogStatusState.value = modelCatalogStatusState.value.copy(
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch { settingsStore.updateAiPreset(preset) }
    }

    fun onAiApiKeyChanged(newKey: String) {
        val preset = activePresetDraft.value
        val sanitized = newKey.trim()
        apiKeyDrafts[preset] = sanitized
        _aiApiKeyDraft.value = sanitized
        aiTestStatusState.value = AiTestState.Idle
        modelCatalogStatusState.value = modelCatalogStatusState.value.copy(
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch { settingsStore.updateAiApiKey(preset, sanitized) }
    }

    fun onAiModelChanged(newModel: String) {
        val preset = activePresetDraft.value
        val sanitized = AiProviderPreset.sanitizeModelId(preset, newModel)
        modelDrafts[preset] = sanitized
        _aiModelDraft.value = sanitized
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateAiModelName(preset, sanitized) }
    }

    fun onAiEndpointChanged(newEndpoint: String) {
        val preset = activePresetDraft.value
        val sanitized = newEndpoint.trim()
        endpointDrafts[preset] = sanitized
        _aiEndpointDraft.value = sanitized
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateAiEndpointUrl(preset, sanitized) }
    }

    fun refreshAiModels() {
        val currentConfig = uiState.value.config.withDrafts()
        val preset = currentConfig.aiPreset
        if (modelCatalogStatusState.value.isFetching) return

        modelCatalogStatusState.value = modelCatalogStatusState.value.copy(
            isFetching = true,
            statusMessage = null,
            errorMessage = null
        )
        viewModelScope.launch {
            try {
                val models = smartAiTester.fetchAvailableModels(currentConfig)
                val updatedMap = modelCatalogStatusState.value.modelsByPreset.toMutableMap().apply {
                    put(preset, models)
                }
                modelCatalogStatusState.value = modelCatalogStatusState.value.copy(
                    modelsByPreset = updatedMap,
                    isFetching = false,
                    statusMessage = "${models.size} güncel ${preset.shortLabelTr} modeli getirildi.",
                    errorMessage = null
                )
                ServiceStateHolder.update { it.copy(availableModelsByPreset = updatedMap) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Model fetch failed for $preset", e)
                modelCatalogStatusState.value = modelCatalogStatusState.value.copy(
                    isFetching = false,
                    statusMessage = null,
                    errorMessage = e.localizedMessage ?: "Model listesi getirilemedi."
                )
            }
        }
    }

    private fun TranslationConfig.withDrafts(): TranslationConfig {
        val preset = activePresetDraft.value
        val activeKey = _aiApiKeyDraft.value.trim()
        val mergedKeys = aiApiKeys.toMutableMap().apply {
            putAll(apiKeyDrafts)
            put(preset, activeKey)
        }
        return copy(
            aiPreset = preset,
            aiApiKey = activeKey,
            aiApiKeys = mergedKeys,
            aiModelName = AiProviderPreset.sanitizeModelId(preset, _aiModelDraft.value),
            aiEndpointUrl = _aiEndpointDraft.value.trim().ifEmpty { preset.defaultBaseUrl }
        )
    }

    fun testAiConnection() {
        val currentConfig = uiState.value.config.withDrafts()
        aiTestStatusState.value = AiTestState.Testing
        viewModelScope.launch {
            try {
                val sample = "Hello hero, ready for battle?"
                val translated = smartAiTester.testConnection(sample, currentConfig)
                aiTestStatusState.value = AiTestState.Success(translated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.example.engine.AiQuotaExceededException) {
                android.util.Log.e("MainViewModel", "AI quota exceeded during test", e)
                aiTestStatusState.value = AiTestState.Error(
                    message = e.localizedMessage ?: "AI kotası aşıldı.",
                    isQuotaError = true
                )
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "AI test failed", e)
                val msg = e.localizedMessage ?: "AI bağlantısı başarısız oldu."
                val looksLikeQuota = msg.contains("quota", ignoreCase = true) ||
                    msg.contains("429") ||
                    msg.contains("kota", ignoreCase = true)
                aiTestStatusState.value = AiTestState.Error(
                    message = msg,
                    isQuotaError = looksLikeQuota
                )
            }
        }
    }

    fun selectSandboxPreset(preset: SandboxPreset) {
        sandboxStatusState.value = SandboxState(selectedPreset = preset)
        viewModelScope.launch {
            settingsStore.updateSourceLanguage(preset.sourceLangCode)
        }
    }

    fun clearSandboxPreview() {
        sandboxStatusState.value = SandboxState(selectedPreset = sandboxStatusState.value.selectedPreset)
    }

    fun runSandboxTranslation(sceneBitmap: Bitmap) {
        val config = uiState.value.config.withDrafts()
        val preset = sandboxStatusState.value.selectedPreset
        val effectiveConfig = config.copy(sourceLangCode = preset.sourceLangCode)
        val isChinese = effectiveConfig.sourceLangCode == TranslateLanguage.CHINESE

        sandboxStatusState.value = sandboxStatusState.value.copy(
            isRunning = true,
            isAiRefining = false,
            errorMessage = null
        )

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val ocrResult = withContext(Dispatchers.Default) {
                    sandboxOcrEngine.recognize(sceneBitmap, effectiveConfig.sourceLangCode)
                }
                if (ocrResult.fullText.isBlank()) {
                    if (!sceneBitmap.isRecycled) sceneBitmap.recycle()
                    sandboxStatusState.value = sandboxStatusState.value.copy(
                        isRunning = false,
                        isAiRefining = false,
                        errorMessage = "Test sahnesinde metin algılanamadı."
                    )
                    return@launch
                }

                val mergedBlocks = BoundingBoxStabilizer.mergeRowBlocks(ocrResult.blocks, isChinese)
                val isMultiBlock = mergedBlocks.size > 1
                val blockTexts = if (isMultiBlock) {
                    mergedBlocks.map { SubtitlePipeline.normalizeText(it.text, isChinese) }
                } else {
                    emptyList()
                }
                val normSource = SubtitlePipeline.normalizeText(ocrResult.fullText, isChinese)

                // Build frosted blur patches once while sceneBitmap is live
                val basePatches: List<InPlaceBlock> = if (isMultiBlock) {
                    mergedBlocks.mapIndexed { idx, block ->
                        val patchResult: BlurredPatchResult = withContext(Dispatchers.Default) {
                            BlurMaskHelper.createFrostedPatch(sceneBitmap, block.boundingBox, effectiveConfig.overlayOpacity)
                        }
                        InPlaceBlock(
                            boundingBox = block.boundingBox,
                            sourceText = blockTexts[idx],
                            translatedText = "",
                            blurredPatch = patchResult.blurredBitmap,
                            sampledBgColor = patchResult.frostedTintColor
                        )
                    }
                } else {
                    val firstBox = mergedBlocks.firstOrNull()?.boundingBox
                        ?: RoiRect(24, (sceneBitmap.height * 0.55f).toInt(), sceneBitmap.width - 48, 80)
                    val patchResult = withContext(Dispatchers.Default) {
                        BlurMaskHelper.createFrostedPatch(sceneBitmap, firstBox, effectiveConfig.overlayOpacity)
                    }
                    listOf(
                        InPlaceBlock(
                            boundingBox = firstBox,
                            sourceText = normSource,
                            translatedText = "",
                            blurredPatch = patchResult.blurredBitmap,
                            sampledBgColor = patchResult.frostedTintColor
                        )
                    )
                }

                if (!sceneBitmap.isRecycled) sceneBitmap.recycle()

                suspend fun runWithProvider(
                    provider: com.example.engine.TranslationProvider,
                    providerConfig: TranslationConfig
                ): Pair<List<InPlaceBlock>, String> {
                    return if (isMultiBlock) {
                        val lines = provider.translateBatch(blockTexts, providerConfig)
                        val blocks = basePatches.mapIndexed { idx, base ->
                            base.copy(translatedText = lines.getOrElse(idx) { "" }.trim())
                        }
                        blocks to blocks.joinToString("\n") { it.translatedText }.trim()
                    } else {
                        val translated = provider.translate(normSource, providerConfig).trim()
                        val blocks = listOf(basePatches.first().copy(translatedText = translated))
                        blocks to translated
                    }
                }

                if (effectiveConfig.engineMode == TranslationEngineMode.HYBRID) {
                    var showedLocal = false
                    val localReady = runCatching { offlineTranslator.isReady(effectiveConfig) }.getOrDefault(false)
                    if (localReady) {
                        val localRes = runCatching { runWithProvider(offlineTranslator, effectiveConfig) }.getOrNull()
                        if (localRes != null && localRes.second.isNotBlank()) {
                            showedLocal = true
                            val localLatency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                            sandboxStatusState.value = sandboxStatusState.value.copy(
                                isRunning = false,
                                isAiRefining = true,
                                recognizedText = ocrResult.fullText,
                                translatedText = localRes.second,
                                inPlaceBlocks = localRes.first,
                                latencyMs = localLatency,
                                errorMessage = null
                            )
                        }
                    }

                    try {
                        val aiConfig = effectiveConfig.copy(engineMode = TranslationEngineMode.SMART_AI)
                        val aiRes = runWithProvider(smartAiTester, aiConfig)
                        val totalLatency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                        sandboxStatusState.value = sandboxStatusState.value.copy(
                            isRunning = false,
                            isAiRefining = false,
                            recognizedText = ocrResult.fullText,
                            translatedText = aiRes.second,
                            inPlaceBlocks = aiRes.first,
                            latencyMs = totalLatency,
                            errorMessage = null
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        sandboxStatusState.value = sandboxStatusState.value.copy(
                            isRunning = false,
                            isAiRefining = false,
                            recognizedText = ocrResult.fullText,
                            errorMessage = if (showedLocal) {
                                "Yerel anlık gösterildi • AI hatası: ${e.localizedMessage ?: "Bağlantı hatası"}"
                            } else {
                                e.localizedMessage ?: "Hibrit AI çevirisi başarısız oldu."
                            }
                        )
                    }
                    return@launch
                }

                val provider = when (effectiveConfig.engineMode) {
                    TranslationEngineMode.SMART_AI -> smartAiTester
                    TranslationEngineMode.OFFLINE_LOCAL -> offlineTranslator
                    TranslationEngineMode.HYBRID -> smartAiTester
                }

                val (inPlaceBlocks, combinedTranslated) = runWithProvider(provider, effectiveConfig)
                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                sandboxStatusState.value = sandboxStatusState.value.copy(
                    isRunning = false,
                    isAiRefining = false,
                    recognizedText = ocrResult.fullText,
                    translatedText = combinedTranslated,
                    inPlaceBlocks = inPlaceBlocks,
                    latencyMs = latency,
                    errorMessage = null
                )
            } catch (e: CancellationException) {
                if (!sceneBitmap.isRecycled) sceneBitmap.recycle()
                throw e
            } catch (e: Exception) {
                if (!sceneBitmap.isRecycled) sceneBitmap.recycle()
                android.util.Log.e("MainViewModel", "Sandbox translation failed", e)
                sandboxStatusState.value = sandboxStatusState.value.copy(
                    isRunning = false,
                    isAiRefining = false,
                    errorMessage = e.localizedMessage ?: "Canlı test çevirisi başarısız oldu."
                )
            }
        }
    }

    fun updateSourceLanguage(language: SupportedLanguage) {
        viewModelScope.launch { settingsStore.updateSourceLanguage(language.mlKitCode) }
    }

    fun updateTargetLanguage(language: SupportedLanguage) {
        viewModelScope.launch { settingsStore.updateTargetLanguage(language.mlKitCode) }
    }

    fun updateDebounceMs(debounceMs: Long) {
        viewModelScope.launch { settingsStore.updateDebounceMs(debounceMs) }
    }

    fun updateScanIntervalMs(scanIntervalMs: Long) {
        viewModelScope.launch { settingsStore.updateScanIntervalMs(scanIntervalMs) }
    }

    fun updateOverlayOpacity(opacity: Float) {
        viewModelScope.launch { settingsStore.updateOverlayOpacity(opacity) }
    }

    fun updateShowOriginalText(show: Boolean) {
        viewModelScope.launch { settingsStore.updateShowOriginalText(show) }
    }

    fun resetRoi() {
        viewModelScope.launch {
            settingsStore.resetRoi(screenBounds.width, screenBounds.height)
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            cacheRepository.clear()
        }
    }

    fun stopCaptureService() {
        ScreenTranslatorService.stopService(appContext)
    }

    override fun onCleared() {
        sandboxOcrEngine.close()
        translatorEngine.close()
        smartAiTester.close()
        super.onCleared()
    }
}
