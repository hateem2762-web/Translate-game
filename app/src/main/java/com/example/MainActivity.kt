package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.configureAppCheck
import com.example.service.ScreenTranslatorService
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.SubLensTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        setContent {
            SubLensTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val aiApiKeyDraft by viewModel.aiApiKeyDraft.collectAsStateWithLifecycle()
                val aiModelDraft by viewModel.aiModelDraft.collectAsStateWithLifecycle()
                val aiEndpointDraft by viewModel.aiEndpointDraft.collectAsStateWithLifecycle()
                val libreEndpointDraft by viewModel.libreEndpointDraft.collectAsStateWithLifecycle()
                val libreApiKeyDraft by viewModel.libreApiKeyDraft.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val overlaySettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.refreshPermissions()
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    viewModel.refreshPermissions()
                }

                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        ScreenTranslatorService.startService(
                            context = this@MainActivity,
                            resultCode = result.resultCode,
                            resultData = data
                        )
                    }
                }

                MainScreen(
                    uiState = uiState,
                    aiApiKeyDraft = aiApiKeyDraft,
                    aiModelDraft = aiModelDraft,
                    aiEndpointDraft = aiEndpointDraft,
                    libreEndpointDraft = libreEndpointDraft,
                    libreApiKeyDraft = libreApiKeyDraft,
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlaySettingsLauncher.launch(intent)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStartCaptureClick = {
                        val projectionManager =
                            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    onStopCaptureClick = { viewModel.stopCaptureService() },
                    onDownloadModelClick = { viewModel.downloadLanguageModels() },
                    onSelectDisplayMode = { viewModel.updateDisplayMode(it) },
                    onSelectEngineMode = { viewModel.updateEngineMode(it) },
                    onFontSizeSpChange = { viewModel.updateFontSizeSp(it) },
                    onInPlacePaddingXChange = { viewModel.updateInPlacePaddingX(it) },
                    onInPlacePaddingYChange = { viewModel.updateInPlacePaddingY(it) },
                    onInPlaceCornerRadiusChange = { viewModel.updateInPlaceCornerRadius(it) },
                    onInPlaceOffsetXChange = { viewModel.updateInPlaceOffsetX(it) },
                    onInPlaceOffsetYChange = { viewModel.updateInPlaceOffsetY(it) },
                    onResetInPlaceTuning = { viewModel.resetInPlaceTuning() },
                    onClassicBoxWidthChange = { viewModel.updateClassicBoxWidthDp(it) },
                    onClassicBoxHeightChange = { viewModel.updateClassicBoxHeightDp(it) },
                    onClassicBoxOpacityChange = { viewModel.updateClassicBoxOpacity(it) },
                    onResetClassicBoxLayout = { viewModel.resetClassicBoxLayout() },
                    onSelectAiPreset = { viewModel.updateAiPreset(it) },
                    onAiApiKeyChange = { viewModel.onAiApiKeyChanged(it) },
                    onAiModelChange = { viewModel.onAiModelChanged(it) },
                    onAiEndpointChange = { viewModel.onAiEndpointChanged(it) },
                    onRefreshAiModelsClick = { viewModel.refreshAiModels() },
                    onLibreEndpointChange = { viewModel.onLibreEndpointChanged(it) },
                    onLibreApiKeyChange = { viewModel.onLibreApiKeyChanged(it) },
                    onTestAiConnectionClick = { viewModel.testAiConnection() },
                    onSelectSourceLanguage = { viewModel.updateSourceLanguage(it) },
                    onSelectTargetLanguage = { viewModel.updateTargetLanguage(it) },
                    onDebounceChange = { viewModel.updateDebounceMs(it) },
                    onScanIntervalChange = { viewModel.updateScanIntervalMs(it) },
                    onOpacityChange = { viewModel.updateOverlayOpacity(it) },
                    onShowOriginalChange = { viewModel.updateShowOriginalText(it) },
                    onResetRoiClick = { viewModel.resetRoi() },
                    onClearCacheClick = { viewModel.clearCache() },
                    onSelectSandboxPreset = { viewModel.selectSandboxPreset(it) },
                    onRunSandboxTest = { viewModel.runSandboxTranslation(it) },
                    onClearSandbox = { viewModel.clearSandboxPreview() }
                )
            }
        }
    }
}
