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
import com.example.engine.MlKitOcrEngine
import com.example.engine.MlKitTranslatorEngine
import com.example.engine.OfflineMlKitTranslationProvider
import com.example.engine.SmartAiTranslationProvider
import com.example.engine.SubtitlePipeline
import com.example.model.AiProviderPreset
import com.example.model.DisplayMode
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

    override fun onCreate() {
        super.onCreate()
        ensureFirebaseInitialized(applicationContext)
        settingsStore = SettingsDataStore(applicationContext)
        val db = SubLensDatabase.getInstance(applicationContext)
        cacheRepository = TranslationCacheRepository(db.translationCacheDao())
        ocrEngine = MlKitOcrEngine()
        offlineProvider = OfflineMlKitTranslationProvider(MlKitTranslatorEngine())
        smartAiProvider = SmartAiTranslationProvider(applicationContext)

        pipeline = SubtitlePipeline(
            scope = serviceScope,
            ocrEngine = ocrEngine,
            providers = mapOf(
                TranslationEngineMode.OFFLINE_LOCAL to offlineProvider,
                TranslationEngineMode.SMART_AI to smartAiProvider
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
            onToggleDisplayMode = { toggleDisplayMode() },
            onToggleEngineMode = { toggleEngineMode() },
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
                currentConfig = config
                if (modeOrEngineChanged || presetOrModelChanged) {
                    pipeline.reset()
                }
                val configured = AiProviderPreset.entries.filter { p ->
                    config.apiKeyFor(p).isNotBlank()
                }.toSet()
                ServiceStateHolder.update {
                    it.copy(
                        displayMode = config.displayMode,
                        engineMode = config.engineMode,
                        fontSizeSp = config.fontSizeSp,
                        inPlacePaddingXdp = config.inPlacePaddingXdp,
                        inPlacePaddingYdp = config.inPlacePaddingYdp,
                        inPlaceCornerRadiusDp = config.inPlaceCornerRadiusDp,
                        inPlaceOffsetXdp = config.inPlaceOffsetXdp,
                        inPlaceOffsetYdp = config.inPlaceOffsetYdp,
                        classicBoxWidthDp = config.classicBoxWidthDp,
                        classicBoxHeightDp = config.classicBoxHeightDp,
                        classicBoxX = config.classicBoxX,
                        classicBoxY = config.classicBoxY,
                        classicBoxOpacity = config.classicBoxOpacity,
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
                        fontSizeSp = currentConfig.fontSizeSp,
                        inPlacePaddingXdp = currentConfig.inPlacePaddingXdp,
                        inPlacePaddingYdp = currentConfig.inPlacePaddingYdp,
                        inPlaceCornerRadiusDp = currentConfig.inPlaceCornerRadiusDp,
                        inPlaceOffsetXdp = currentConfig.inPlaceOffsetXdp,
                        inPlaceOffsetYdp = currentConfig.inPlaceOffsetYdp,
                        classicBoxWidthDp = currentConfig.classicBoxWidthDp,
                        classicBoxHeightDp = currentConfig.classicBoxHeightDp,
                        classicBoxX = currentConfig.classicBoxX,
                        classicBoxY = currentConfig.classicBoxY,
                        classicBoxOpacity = currentConfig.classicBoxOpacity,
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
                        inPlacePaddingXdp = currentConfig.inPlacePaddingXdp,
                        inPlacePaddingYdp = currentConfig.inPlacePaddingYdp,
                        inPlaceCornerRadiusDp = currentConfig.inPlaceCornerRadiusDp,
                        inPlaceOffsetXdp = currentConfig.inPlaceOffsetXdp,
                        inPlaceOffsetYdp = currentConfig.inPlaceOffsetYdp,
                        classicBoxWidthDp = currentConfig.classicBoxWidthDp,
                        classicBoxHeightDp = currentConfig.classicBoxHeightDp,
                        classicBoxX = currentConfig.classicBoxX,
                        classicBoxY = currentConfig.classicBoxY,
                        classicBoxOpacity = currentConfig.classicBoxOpacity,
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
                val isInPlaceWithActiveMask =
                    currentConfig.displayMode == DisplayMode.IN_PLACE_BLUR &&
                        overlayController.hasActiveInPlaceOverlay()

                if (!isPaused && !overlayController.isHistoryVisible()) {
                    val roi = overlayController.getActiveCaptureRoi()
                    val croppedBitmap = if (isInPlaceWithActiveMask) {
                        // Drain stale frames, wait for Choreographer invisible draw commit,
                        // capture ONLY frames >= minValidTsNs, then restore overlay immediately.
                        captureManager.drainPendingFrame()
                        val minValidTsNs = overlayController.hideInPlaceAndAwaitCommitNs()
                        try {
                            captureManager.awaitAndCaptureStealthRoi(
                                roi = roi,
                                minValidTimestampNs = minValidTsNs,
                                settleMs = 38L,
                                maxWaitMs = 110L
                            )
                        } finally {
                            overlayController.setInPlaceStealth(false)
                        }
                    } else {
                        withContext(Dispatchers.Default) {
                            captureManager.captureCroppedRoi(roi)
                        }
                    }

                    if (croppedBitmap != null) {
                        pipeline.processFrame(croppedBitmap, currentConfig)
                    }
                }

                val loopDelay = when {
                    isInPlaceWithActiveMask && pipeline.isAwaitingBlankClear() -> 120L
                    isInPlaceWithActiveMask -> maxOf(currentConfig.scanIntervalMs, 380L)
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

    private fun toggleDisplayMode() {
        val next = if (currentConfig.displayMode == DisplayMode.CLASSIC_BOX) {
            DisplayMode.IN_PLACE_BLUR
        } else {
            DisplayMode.CLASSIC_BOX
        }
        serviceScope.launch(Dispatchers.IO) {
            settingsStore.updateDisplayMode(next)
        }
    }

    private fun toggleEngineMode() {
        val modes = TranslationEngineMode.entries
        val next = modes[(modes.indexOf(currentConfig.engineMode) + 1) % modes.size]
        serviceScope.launch(Dispatchers.IO) {
            settingsStore.updateEngineMode(next)
        }
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
