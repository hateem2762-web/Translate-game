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
import com.example.engine.MlKitOcrEngine
import com.example.engine.MlKitTranslatorEngine
import com.example.engine.OfflineMlKitTranslationProvider
import com.example.engine.SmartAiTranslationProvider
import com.example.engine.SubtitlePipeline
import com.example.model.AiProviderPreset
import com.example.model.DisplayMode
import com.example.model.InPlaceBlock
import com.example.model.ModelDownloadState
import com.example.model.RoiRect
import com.example.model.SubtitleFontSize
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
        labelTr = "İngilizce Diyalog",
        sourceLangCode = TranslateLanguage.ENGLISH,
        sampleText = "The ancient seal is breaking! Find the key before the shadow consumes the village."
    ),
    CHINESE_RPG(
        labelTr = "Çince Diyalog",
        sourceLangCode = TranslateLanguage.CHINESE,
        sampleText = "古老的封印正在碎裂！在黑暗吞噬村庄之前，快找到钥匙。"
    )
}

data class SandboxState(
    val selectedPreset: SandboxPreset = SandboxPreset.ENGLISH_RPG,
    val isRunning: Boolean = false,
    val recognizedText: String = "",
    val translatedText: String = "",
    val inPlaceBlocks: List<InPlaceBlock> = emptyList(),
    val latencyMs: Long = 0L,
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
    val sandboxState: SandboxState = SandboxState()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsStore = SettingsDataStore(appContext)
    private val cacheRepository = TranslationCacheRepository(
        SubLensDatabase.getInstance(appContext).translationCacheDao()
    )
    private val translatorEngine = MlKitTranslatorEngine()
    private val offlineTranslator = OfflineMlKitTranslationProvider(translatorEngine)
    private val smartAiTester = SmartAiTranslationProvider()
    private val sandboxOcrEngine = MlKitOcrEngine()

    private val screenBounds = ScreenMetricsHelper.getScreenBounds(appContext)

    private val permissionState = MutableStateFlow(readPermissions())
    private val modelStatusState = MutableStateFlow(ModelDownloadState.UNKNOWN to (null as String?))
    private val aiTestStatusState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    private val sandboxStatusState = MutableStateFlow(SandboxState())

    // Synchronous text-field drafts backed immediately by DataStore so typing/pasting never drops
    private val _aiApiKeyDraft = MutableStateFlow("")
    val aiApiKeyDraft: StateFlow<String> = _aiApiKeyDraft.asStateFlow()

    private val _aiModelDraft = MutableStateFlow(AiProviderPreset.GEMINI_FLASH.defaultModel)
    val aiModelDraft: StateFlow<String> = _aiModelDraft.asStateFlow()

    private val _aiEndpointDraft = MutableStateFlow(AiProviderPreset.GEMINI_FLASH.defaultBaseUrl)
    val aiEndpointDraft: StateFlow<String> = _aiEndpointDraft.asStateFlow()

    val uiState: StateFlow<MainUiState> = combine(
        permissionState,
        settingsStore.configFlow,
        settingsStore.observeRoi(screenBounds.width, screenBounds.height),
        modelStatusState,
        cacheRepository.observeCacheCount(),
        ServiceStateHolder.telemetry,
        aiTestStatusState,
        sandboxStatusState
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
            sandboxState = sandboxState
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
            _aiApiKeyDraft.value = initialConfig.aiApiKey
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
        viewModelScope.launch { settingsStore.updateEngineMode(mode) }
    }

    fun updateFontSize(fontSize: SubtitleFontSize) {
        viewModelScope.launch { settingsStore.updateFontSize(fontSize) }
    }

    fun updateAiPreset(preset: AiProviderPreset) {
        _aiEndpointDraft.value = preset.defaultBaseUrl
        _aiModelDraft.value = preset.defaultModel
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateAiPreset(preset) }
    }

    fun onAiApiKeyChanged(newKey: String) {
        val sanitized = newKey.trim()
        _aiApiKeyDraft.value = sanitized
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateAiApiKey(sanitized) }
    }

    fun onAiModelChanged(newModel: String) {
        val sanitized = newModel.trim()
        _aiModelDraft.value = sanitized
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateAiModelName(sanitized) }
    }

    fun onAiEndpointChanged(newEndpoint: String) {
        val sanitized = newEndpoint.trim()
        _aiEndpointDraft.value = sanitized
        aiTestStatusState.value = AiTestState.Idle
        viewModelScope.launch { settingsStore.updateAiEndpointUrl(sanitized) }
    }

    fun testAiConnection() {
        val currentConfig = uiState.value.config.copy(
            aiApiKey = _aiApiKeyDraft.value.trim(),
            aiModelName = _aiModelDraft.value.trim(),
            aiEndpointUrl = _aiEndpointDraft.value.trim()
        )
        aiTestStatusState.value = AiTestState.Testing
        viewModelScope.launch {
            try {
                val translated = smartAiTester.testConnection("Hello hero, ready for battle?", currentConfig)
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
        val config = uiState.value.config.copy(
            aiApiKey = _aiApiKeyDraft.value.trim(),
            aiModelName = _aiModelDraft.value.trim(),
            aiEndpointUrl = _aiEndpointDraft.value.trim()
        )
        val preset = sandboxStatusState.value.selectedPreset
        val effectiveConfig = config.copy(sourceLangCode = preset.sourceLangCode)
        val isChinese = effectiveConfig.sourceLangCode == TranslateLanguage.CHINESE

        sandboxStatusState.value = sandboxStatusState.value.copy(
            isRunning = true,
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
                        errorMessage = "Test sahnesinde metin algılanamadı."
                    )
                    return@launch
                }

                val provider = if (effectiveConfig.engineMode == TranslationEngineMode.SMART_AI) {
                    smartAiTester
                } else {
                    offlineTranslator
                }

                val inPlaceBlocks: List<InPlaceBlock>
                val combinedTranslated: String

                if (ocrResult.blocks.size > 1) {
                    val blockTexts = ocrResult.blocks.map { SubtitlePipeline.normalizeText(it.text, isChinese) }
                    val translatedLines = provider.translateBatch(blockTexts, effectiveConfig)
                    inPlaceBlocks = ocrResult.blocks.mapIndexed { idx, block ->
                        val translatedLine = translatedLines.getOrElse(idx) { "" }.trim()
                        val patchResult: BlurredPatchResult = withContext(Dispatchers.Default) {
                            BlurMaskHelper.createFrostedPatch(sceneBitmap, block.boundingBox, effectiveConfig.overlayOpacity)
                        }
                        InPlaceBlock(
                            boundingBox = block.boundingBox,
                            sourceText = blockTexts[idx],
                            translatedText = translatedLine,
                            blurredPatch = patchResult.blurredBitmap,
                            sampledBgColor = patchResult.frostedTintColor
                        )
                    }
                    combinedTranslated = inPlaceBlocks.joinToString("\n") { it.translatedText }.trim()
                } else {
                    val normSource = SubtitlePipeline.normalizeText(ocrResult.fullText, isChinese)
                    combinedTranslated = provider.translate(normSource, effectiveConfig).trim()
                    val firstBox = ocrResult.blocks.firstOrNull()?.boundingBox
                        ?: RoiRect(24, (sceneBitmap.height * 0.55f).toInt(), sceneBitmap.width - 48, 80)
                    val patchResult = withContext(Dispatchers.Default) {
                        BlurMaskHelper.createFrostedPatch(sceneBitmap, firstBox, effectiveConfig.overlayOpacity)
                    }
                    inPlaceBlocks = listOf(
                        InPlaceBlock(
                            boundingBox = firstBox,
                            sourceText = normSource,
                            translatedText = combinedTranslated,
                            blurredPatch = patchResult.blurredBitmap,
                            sampledBgColor = patchResult.frostedTintColor
                        )
                    )
                }

                if (!sceneBitmap.isRecycled) sceneBitmap.recycle()

                val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                sandboxStatusState.value = sandboxStatusState.value.copy(
                    isRunning = false,
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
