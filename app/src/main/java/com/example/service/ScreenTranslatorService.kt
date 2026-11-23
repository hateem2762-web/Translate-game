package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.ensureFirebaseInitialized
import com.example.capture.ScreenCaptureManager
import com.example.capture.ScreenMetricsHelper
import com.example.data.local.SubLensDatabase
import com.example.data.preferences.SettingsDataStore
import com.example.data.repository.TranslationCacheRepository
import com.example.engine.FreeWebTranslationProvider
import com.example.engine.MlKitOcrEngine
import com.example.engine.MlKitTranslatorEngine
import com.example.engine.OfflineMlKitTranslationProvider
import com.example.engine.SmartAiTranslationProvider
import com.example.engine.SubtitlePipeline
import com.example.model.AiProviderPreset
import com.example.model.DisplayMode
import com.example.model.OverlayHubStage
import com.example.model.ServiceStatus
import com.example.model.TranslationConfig
import com.example.model.TranslationEngineMode
import com.example.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenTranslatorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsStore: SettingsDataStore
    private lateinit var cacheRepository: TranslationCacheRepository
    private lateinit var ocrEngine: MlKitOcrEngine
    private lateinit var offlineProvider: OfflineMlKitTranslationProvider
    private lateinit var smartAiProvider: SmartAiTranslationProvider
    private lateinit var pipeline: SubtitlePipeline
    private lateinit var captureManager: ScreenCaptureManager
    private lateinit var overlayController: OverlayController

    private var currentConfig: TranslationConfig = TranslationConfig()
    private var captureLoopJob: Job? = null
    private var isPaused: Boolean = false
    private var isRoiLocked: Boolean = false
    private var currentHubStage: OverlayHubStage = OverlayHubStage.BUBBLE_IDLE
    private var lastInPlaceStealthPeekMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        ensureFirebaseInitialized(applicationContext)
        settingsStore = SettingsDataStore(applicationContext)
        val db = SubLensDatabase.getInstance(applicationContext)
        cacheRepository = TranslationCacheRepository(db.translationCacheDao())
        ocrEngine = MlKitOcrEngine()
        offlineProvider = OfflineMlKitTranslationProvider(MlKitTranslatorEngine())
        smartAiProvider = SmartAiTranslationProvider(applicationContext)

        val freeWebProvider = FreeWebTranslationProvider()

        pipeline = SubtitlePipeline(
            scope = serviceScope,
            ocrEngine = ocrEngine,
            providers = mapOf(
                TranslationEngineMode.OFFLINE_LOCAL to offlineProvider,
                TranslationEngineMode.HYBRID to smartAiProvider,
                TranslationEngineMode.SMART_AI to smartAiProvider,
                TranslationEngineMode.GOOGLE_FREE to freeWebProvider,
                TranslationEngineMode.BING_FREE to freeWebProvider,
                TranslationEngineMode.BAIDU_FREE to freeWebProvider
            ),
            cacheStore = cacheRepository
        )

        captureManager = ScreenCaptureManager(
            context = applicationContext,
            onProjectionStopped = {
                serviceScope.launch { stopAndCleanup() }
            }
        )

        overlayController = OverlayController(
            context = applicationContext,
            onRoiChanged = { newRoi ->
                pipeline.invalidateVisualCache()
                pipeline.clearActiveSubtitle()
                serviceScope.launch(Dispatchers.IO) {
                    settingsStore.saveRoi(newRoi)
                }
            },
            onClassicBoxLayoutChanged = { x, y, widthDp, heightDp ->
                serviceScope.launch(Dispatchers.IO) {
                    settingsStore.saveClassicBoxLayout(x, y, widthDp, heightDp)
                }
            },
            onSelectTranslationMode = { mode -> selectTranslationModeFromBubble(mode) },
            onCloseActiveMode = { closeActiveModeToBubble() },
            onToggleDisplayMode = { toggleDisplayMode() },
            onSelectEngineMode = { mode -> selectEngineMode(mode) },
            onOpenEnginePicker = {
                overlayController.showEnginePicker(ServiceStateHolder.telemetry.value)
            },
            onOpenModelPicker = {
                overlayController.showModelPicker(ServiceStateHolder.telemetry.value)
            },
            onSelectAiPresetFromOverlay = { preset ->
                serviceScope.launch(Dispatchers.IO) {
                    settingsStore.updateAiPreset(preset)
                }
            },
            onSelectAiModelFromOverlay = { preset, modelId ->
                pipeline.reset()
                serviceScope.launch(Dispatchers.IO) {
                    settingsStore.updateAiPreset(preset)
                    settingsStore.updateAiModelName(preset, modelId)
                }
            },
            onRefreshModelsFromOverlay = { preset ->
                refreshModelsInsideOverlay(preset)
            },
            onTogglePause = { togglePause() },
            onToggleLockRoi = { toggleRoiLock() },
            onOpenHistory = {
                overlayController.showHistory(ServiceStateHolder.telemetry.value.recentEntries)
            },
            onClearHistory = {
                ServiceStateHolder.clearHistory()
            },
            onOpenSettings = { openMainApp() },
            onStopService = { stopAndCleanup() }
        )

        observeConfigAndPipeline()
    }

    private fun observeConfigAndPipeline() {
        serviceScope.launch {
            settingsStore.configFlow.collect { config ->
                val presetOrModelChanged = config.aiPreset != currentConfig.aiPreset ||
                    config.aiModelName != currentConfig.aiModelName ||
                    config.aiApiKey != currentConfig.aiApiKey
                val modeOrEngineChanged = config.displayMode != currentConfig.displayMode ||
                    config.engineMode != currentConfig.engineMode
                val blurAppearanceChanged = config.inPlaceBlurStyle != currentConfig.inPlaceBlurStyle ||
                    config.inPlaceBlurIntensity != currentConfig.inPlaceBlurIntensity ||
                    config.overlayOpacity != currentConfig.overlayOpacity
                currentConfig = config
                if (modeOrEngineChanged || presetOrModelChanged) {
                    pipeline.reset()
                } else if (blurAppearanceChanged) {
                    pipeline.invalidateVisualCache()
                    if (config.displayMode == DisplayMode.IN_PLACE_BLUR) {
                        pipeline.clearActiveSubtitle()
                    }
                }
                val configured = AiProviderPreset.entries.filter { p ->
                    config.apiKeyFor(p).isNotBlank()
                }.toSet()
                ServiceStateHolder.update {
                    it.copy(
                        displayMode = config.displayMode,
                        engineMode = config.engineMode,
                        bubbleBehavior = config.bubbleBehavior,
                        bubbleTheme = config.bubbleTheme,
                        overlayHubStage = currentHubStage,
                        fontSizeSp = config.fontSizeSp,
                        subtitleTextColorArgb = config.subtitleTextColorArgb,
                        inPlacePaddingXdp = config.inPlacePaddingXdp,
                        inPlacePaddingYdp = config.inPlacePaddingYdp,
                        inPlaceCornerRadiusDp = config.inPlaceCornerRadiusDp,
                        inPlaceOffsetXdp = config.inPlaceOffsetXdp,
                        inPlaceOffsetYdp = config.inPlaceOffsetYdp,
                        inPlaceBlurStyle = config.inPlaceBlurStyle,
                        inPlaceBlurIntensity = config.inPlaceBlurIntensity,
                        classicBoxWidthDp = config.classicBoxWidthDp,
                        classicBoxHeightDp = config.classicBoxHeightDp,
                        classicBoxX = config.classicBoxX,
                        classicBoxY = config.classicBoxY,
                        classicBoxOpacity = config.classicBoxOpacity,
                        classicTextFitMode = config.classicTextFitMode,
                        aiPreset = config.aiPreset,
                        aiModelName = config.aiModelName,
                        configuredPresets = configured
                    )
                }
                overlayController.updateHud(
                    telemetry = ServiceStateHolder.telemetry.value,
                    showOriginal = config.showOriginalText
                )
            }
        }

        serviceScope.launch {
            pipeline.output.collect { out ->
                out.lastCompletedEntry?.let { entry ->
                    ServiceStateHolder.pushCompletedEntry(entry)
                }
                val configured = AiProviderPreset.entries.filter { p ->
                    currentConfig.apiKeyFor(p).isNotBlank()
                }.toSet()
                ServiceStateHolder.update { prev ->
                    prev.copy(
                        displayMode = currentConfig.displayMode,
                        engineMode = currentConfig.engineMode,
                        bubbleBehavior = currentConfig.bubbleBehavior,
                        bubbleTheme = currentConfig.bubbleTheme,
                        overlayHubStage = currentHubStage,
                        fontSizeSp = currentConfig.fontSizeSp,
                        subtitleTextColorArgb = currentConfig.subtitleTextColorArgb,
                        inPlacePaddingXdp = currentConfig.inPlacePaddingXdp,
                        inPlacePaddingYdp = currentConfig.inPlacePaddingYdp,
                        inPlaceCornerRadiusDp = currentConfig.inPlaceCornerRadiusDp,
                        inPlaceOffsetXdp = currentConfig.inPlaceOffsetXdp,
                        inPlaceOffsetYdp = currentConfig.inPlaceOffsetYdp,
                        inPlaceBlurStyle = currentConfig.inPlaceBlurStyle,
                        inPlaceBlurIntensity = currentConfig.inPlaceBlurIntensity,
                        classicBoxWidthDp = currentConfig.classicBoxWidthDp,
                        classicBoxHeightDp = currentConfig.classicBoxHeightDp,
                        classicBoxX = currentConfig.classicBoxX,
                        classicBoxY = currentConfig.classicBoxY,
                        classicBoxOpacity = currentConfig.classicBoxOpacity,
                        classicTextFitMode = currentConfig.classicTextFitMode,
                        aiPreset = currentConfig.aiPreset,
                        aiModelName = currentConfig.aiModelName,
                        configuredPresets = configured,
                        currentSourceText = out.sourceText,
                        currentTranslatedText = out.translatedText,
                        inPlaceBlocks = out.inPlaceBlocks,
                        isStabilizing = out.isStabilizing,
                        isAiRefining = out.isAiRefining,
                        lastFromCache = out.fromCache,
                        lastLatencyMs = out.latencyMs,
                        lastResultEngineMode = out.resultEngineMode ?: currentConfig.engineMode,
                        framesProcessed = out.framesProcessed,
                        framesSkippedSameText = out.framesSkipped,
                        cacheHits = out.cacheHits,
                        errorMessage = out.errorMessage
                    )
                }
                overlayController.updateHud(
                    telemetry = ServiceStateHolder.telemetry.value,
                    showOriginal = currentConfig.showOriginalText
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndCleanup()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                togglePause()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    ServiceStateHolder.update {
                        it.copy(status = ServiceStatus.ERROR, errorMessage = "Ekran yakalama izni alınamadı.")
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!Settings.canDrawOverlays(this)) {
                    ServiceStateHolder.update {
                        it.copy(status = ServiceStatus.ERROR, errorMessage = "Üstte gösterme izni gerekli.")
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithNotification(paused = false)
                startSession(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    private fun startSession(resultCode: Int, resultData: Intent) {
        serviceScope.launch {
            try {
                isPaused = false
                isRoiLocked = false
                currentHubStage = OverlayHubStage.BUBBLE_IDLE
                lastInPlaceStealthPeekMs = 0L
                pipeline.reset()

                val bounds = ScreenMetricsHelper.getScreenBounds(applicationContext)
                val savedRoi = settingsStore.observeRoi(bounds.width, bounds.height).first()
                currentConfig = settingsStore.configFlow.first()

                overlayController.show(savedRoi, currentConfig)
                captureManager.start(resultCode, resultData)

                val configured = AiProviderPreset.entries.filter { p ->
                    currentConfig.apiKeyFor(p).isNotBlank()
                }.toSet()
                ServiceStateHolder.update {
                    it.copy(
                        status = ServiceStatus.RUNNING,
                        displayMode = currentConfig.displayMode,
                        engineMode = currentConfig.engineMode,
                        fontSizeSp = currentConfig.fontSizeSp,
                        subtitleTextColorArgb = currentConfig.subtitleTextColorArgb,
                        inPlacePaddingXdp = currentConfig.inPlacePaddingXdp,
                        inPlacePaddingYdp = currentConfig.inPlacePaddingYdp,
                        inPlaceCornerRadiusDp = currentConfig.inPlaceCornerRadiusDp,
                        inPlaceOffsetXdp = currentConfig.inPlaceOffsetXdp,
                        inPlaceOffsetYdp = currentConfig.inPlaceOffsetYdp,
                        inPlaceBlurStyle = currentConfig.inPlaceBlurStyle,
                        inPlaceBlurIntensity = currentConfig.inPlaceBlurIntensity,
                        classicBoxWidthDp = currentConfig.classicBoxWidthDp,
                        classicBoxHeightDp = currentConfig.classicBoxHeightDp,
                        classicBoxX = currentConfig.classicBoxX,
                        classicBoxY = currentConfig.classicBoxY,
                        classicBoxOpacity = currentConfig.classicBoxOpacity,
                        classicTextFitMode = currentConfig.classicTextFitMode,
                        bubbleBehavior = currentConfig.bubbleBehavior,
                        bubbleTheme = currentConfig.bubbleTheme,
                        overlayHubStage = currentHubStage,
                        aiPreset = currentConfig.aiPreset,
                        aiModelName = currentConfig.aiModelName,
                        configuredPresets = configured,
                        isRoiLocked = false,
                        isAiRefining = false,
                        errorMessage = null
                    )
                }
                overlayController.updateHud(
                    telemetry = ServiceStateHolder.telemetry.value,
                    showOriginal = currentConfig.showOriginalText
                )

                startCaptureLoop()
            } catch (e: Exception) {
                ServiceStateHolder.update {
                    it.copy(
                        status = ServiceStatus.ERROR,
                        errorMessage = "Başlatma hatası: ${e.localizedMessage ?: "Bilinmeyen hata"}"
                    )
                }
                stopAndCleanup()
            }
        }
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = serviceScope.launch {
            while (isActive) {
                val isModeActive = currentHubStage == OverlayHubStage.ACTIVE_MODE
                val isInPlaceWithActiveMask =
                    isModeActive &&
                        currentConfig.displayMode == DisplayMode.IN_PLACE_BLUR &&
                        overlayController.hasActiveInPlaceOverlay()

                if (isModeActive && !isPaused && !overlayController.isHistoryVisible()) {
                    val roi = overlayController.getActiveCaptureRoi()
                    val cleanBitmap = if (isInPlaceWithActiveMask) {
                        val isStabilizing = pipeline.isStabilizingOrPending()
                        val nowMs = System.currentTimeMillis()
                        val shouldStealthCapture = when {
                            isStabilizing -> false
                            pipeline.isAwaitingBlankClear() -> true
                            !pipeline.canStealthPeekNow(nowMs) -> {
                                pipeline.recordPassiveSkip()
                                false
                            }
                            else -> {
                                val passiveBitmap = withContext(Dispatchers.Default) {
                                    captureManager.captureCroppedRoi(roi)
                                }
                                val outerChanged = if (passiveBitmap == null) {
                                    false
                                } else {
                                    val (maskRects, maskSignature) = overlayController.getActiveMaskRectsInRoi()
                                    pipeline.hasUnmaskedVisualChange(
                                        bitmap = passiveBitmap,
                                        maskedRects = maskRects,
                                        maskSignature = maskSignature
                                    )
                                }
                                // When outer pixels are static (e.g. dark subtitle bar / tight ROI), still
                                // perform a single ultra-fast ~34ms peek every ~1.35s so new text underneath
                                // is always detected instead of staying occluded behind the blur.
                                val periodicDue = nowMs - lastInPlaceStealthPeekMs >= PERIODIC_BLUR_PEEK_MS
                                if (!outerChanged && !periodicDue) {
                                    pipeline.recordPassiveSkip()
                                    false
                                } else {
                                    true
                                }
                            }
                        }

                        if (shouldStealthCapture) {
                            lastInPlaceStealthPeekMs = System.currentTimeMillis()
                            captureManager.drainPendingFrame()
                            val minValidTsNs = overlayController.hideInPlaceAndAwaitCommitNs()
                            try {
                                captureManager.awaitAndCaptureStealthRoi(
                                    roi = roi,
                                    minValidTimestampNs = minValidTsNs,
                                    settleMs = 24L,
                                    maxWaitMs = 140L
                                )
                            } finally {
                                overlayController.setInPlaceStealth(false)
                            }
                        } else {
                            null
                        }
                    } else {
                        withContext(Dispatchers.Default) {
                            captureManager.captureCroppedRoi(roi)
                        }
                    }

                    if (cleanBitmap != null) {
                        pipeline.processFrame(cleanBitmap, currentConfig)
                    }
                }

                val loopDelay = when {
                    !isModeActive -> 240L
                    isInPlaceWithActiveMask && pipeline.isStabilizingOrPending() -> 180L
                    isInPlaceWithActiveMask && pipeline.isAwaitingBlankClear() -> 300L
                    isInPlaceWithActiveMask -> maxOf(currentConfig.scanIntervalMs, 650L)
                    else -> currentConfig.scanIntervalMs.coerceIn(150L, 1200L)
                }
                delay(loopDelay)
            }
        }
    }

    private fun refreshModelsInsideOverlay(preset: AiProviderPreset) {
        val targetConfig = currentConfig.copy(
            aiPreset = preset,
            aiEndpointUrl = currentConfig.endpointFor(preset),
            aiModelName = currentConfig.modelFor(preset),
            aiApiKey = currentConfig.apiKeyFor(preset)
        )
        overlayController.updateModelPickerState(
            telemetry = ServiceStateHolder.telemetry.value,
            refreshing = true,
            note = "${preset.shortLabelTr} modelleri getiriliyor…"
        )
        serviceScope.launch {
            try {
                val models = smartAiProvider.fetchAvailableModels(targetConfig)
                val nextMap = ServiceStateHolder.telemetry.value.availableModelsByPreset.toMutableMap().apply {
                    put(preset, models)
                }
                ServiceStateHolder.update { it.copy(availableModelsByPreset = nextMap) }
                overlayController.updateModelPickerState(
                    telemetry = ServiceStateHolder.telemetry.value,
                    refreshing = false,
                    note = "${models.size} güncel model listelendi."
                )
            } catch (e: Exception) {
                overlayController.updateModelPickerState(
                    telemetry = ServiceStateHolder.telemetry.value,
                    refreshing = false,
                    note = e.localizedMessage ?: "Model listesi getirilemedi."
                )
            }
        }
    }

    private fun selectTranslationModeFromBubble(mode: DisplayMode) {
        currentHubStage = OverlayHubStage.ACTIVE_MODE
        lastInPlaceStealthPeekMs = 0L
        pipeline.reset()
        if (currentConfig.displayMode != mode) {
            currentConfig = currentConfig.copy(displayMode = mode)
            serviceScope.launch(Dispatchers.IO) {
                settingsStore.updateDisplayMode(mode)
            }
        }
        ServiceStateHolder.update {
            it.copy(
                overlayHubStage = OverlayHubStage.ACTIVE_MODE,
                displayMode = mode,
                currentSourceText = "",
                currentTranslatedText = "",
                inPlaceBlocks = emptyList(),
                isStabilizing = false,
                isAiRefining = false,
                errorMessage = null
            )
        }
        overlayController.updateHud(
            telemetry = ServiceStateHolder.telemetry.value,
            showOriginal = currentConfig.showOriginalText
        )
    }

    private fun closeActiveModeToBubble() {
        currentHubStage = OverlayHubStage.BUBBLE_IDLE
        lastInPlaceStealthPeekMs = 0L
        pipeline.reset()
        ServiceStateHolder.update {
            it.copy(
                overlayHubStage = OverlayHubStage.BUBBLE_IDLE,
                currentSourceText = "",
                currentTranslatedText = "",
                inPlaceBlocks = emptyList(),
                isStabilizing = false,
                isAiRefining = false,
                errorMessage = null
            )
        }
        overlayController.updateHud(
            telemetry = ServiceStateHolder.telemetry.value,
            showOriginal = currentConfig.showOriginalText
        )
    }

    private fun toggleDisplayMode() {
        val next = if (currentConfig.displayMode == DisplayMode.CLASSIC_BOX) {
            DisplayMode.IN_PLACE_BLUR
        } else {
            DisplayMode.CLASSIC_BOX
        }
        selectTranslationModeFromBubble(next)
    }

    private fun selectEngineMode(mode: TranslationEngineMode) {
        if (currentConfig.engineMode == mode) return
        pipeline.reset()
        currentConfig = currentConfig.copy(engineMode = mode)
        serviceScope.launch(Dispatchers.IO) {
            settingsStore.updateEngineMode(mode)
        }
        ServiceStateHolder.update {
            it.copy(
                engineMode = mode,
                lastResultEngineMode = mode,
                currentSourceText = "",
                currentTranslatedText = "",
                inPlaceBlocks = emptyList(),
                isStabilizing = false,
                isAiRefining = false,
                errorMessage = null
            )
        }
        overlayController.updateHud(
            telemetry = ServiceStateHolder.telemetry.value,
            showOriginal = currentConfig.showOriginalText
        )
    }

    private fun togglePause() {
        isPaused = !isPaused
        val newStatus = if (isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING
        ServiceStateHolder.update { it.copy(status = newStatus) }
        overlayController.updateHud(
            telemetry = ServiceStateHolder.telemetry.value,
            showOriginal = currentConfig.showOriginalText
        )
        startForegroundWithNotification(paused = isPaused)
    }

    private fun toggleRoiLock() {
        isRoiLocked = !isRoiLocked
        overlayController.setRoiLocked(isRoiLocked)
        ServiceStateHolder.update { it.copy(isRoiLocked = isRoiLocked) }
        overlayController.updateHud(
            telemetry = ServiceStateHolder.telemetry.value,
            showOriginal = currentConfig.showOriginalText
        )
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun stopAndCleanup() {
        captureLoopJob?.cancel()
        captureLoopJob = null
        overlayController.hide()
        captureManager.release()
        ServiceStateHolder.markStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification(paused: Boolean) {
        ensureNotificationChannel()
        val notification = buildNotification(paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            101,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, ScreenTranslatorService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (paused) {
            getString(R.string.notification_text_paused)
        } else {
            getString(R.string.notification_text_running)
        }

        val pauseLabel = if (paused) {
            getString(R.string.action_resume_capture)
        } else {
            getString(R.string.action_pause_capture)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sublens)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(bodyText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, getString(R.string.action_stop_capture), stopIntent)
            .build()
    }

    override fun onDestroy() {
        captureLoopJob?.cancel()
        overlayController.hide()
        captureManager.release()
        ocrEngine.close()
        offlineProvider.close()
        smartAiProvider.close()
        ServiceStateHolder.markStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sublens_capture_channel"
        private const val NOTIFICATION_ID = 4201
        private const val PERIODIC_BLUR_PEEK_MS = 1350L

        const val ACTION_START = "com.example.action.START_SUBTITLE_SERVICE"
        const val ACTION_STOP = "com.example.action.STOP_SUBTITLE_SERVICE"
        const val ACTION_TOGGLE_PAUSE = "com.example.action.TOGGLE_PAUSE_SUBTITLE_SERVICE"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        fun startService(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenTranslatorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            context.startService(createStopIntent(context))
        }

        private fun createStopIntent(context: Context): Intent {
            return Intent(context, ScreenTranslatorService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
