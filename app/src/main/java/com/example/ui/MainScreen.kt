package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AiProviderPreset
import com.example.model.ClassicTextFitMode
import com.example.model.DisplayMode
import com.example.model.FloatingBubbleBehavior
import com.example.model.FloatingBubbleTheme
import com.example.model.ModelDownloadState
import com.example.model.OverlayHubStage
import com.example.model.ServiceStatus
import com.example.model.SupportedLanguage
import com.example.model.TranslationEngineMode
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated

private enum class MainTab {
    GENERAL_AI,
    APPEARANCE,
    SANDBOX_HISTORY
}

@Composable
fun MainScreen(
    uiState: MainUiState,
    aiApiKeyDraft: String,
    aiModelDraft: String,
    aiEndpointDraft: String,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit,
    onDownloadModelClick: () -> Unit,
    onSelectDisplayMode: (DisplayMode) -> Unit,
    onSelectEngineMode: (TranslationEngineMode) -> Unit,
    onBubbleBehaviorChange: (FloatingBubbleBehavior) -> Unit,
    onBubbleThemeChange: (FloatingBubbleTheme) -> Unit,
    onFontSizeSpChange: (Float) -> Unit,
    onSubtitleTextColorChange: (Int) -> Unit,
    onClassicTextFitModeChange: (ClassicTextFitMode) -> Unit,
    onInPlacePaddingXChange: (Float) -> Unit,
    onInPlacePaddingYChange: (Float) -> Unit,
    onInPlaceCornerRadiusChange: (Float) -> Unit,
    onInPlaceOffsetXChange: (Float) -> Unit,
    onInPlaceOffsetYChange: (Float) -> Unit,
    onResetInPlaceTuning: () -> Unit,
    onClassicBoxWidthChange: (Int) -> Unit,
    onClassicBoxHeightChange: (Int) -> Unit,
    onClassicBoxOpacityChange: (Float) -> Unit,
    onResetClassicBoxLayout: () -> Unit,
    onSelectAiPreset: (AiProviderPreset) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiEndpointChange: (String) -> Unit,
    onRefreshAiModelsClick: () -> Unit,
    onTestAiConnectionClick: () -> Unit,
    onSelectSourceLanguage: (SupportedLanguage) -> Unit,
    onSelectTargetLanguage: (SupportedLanguage) -> Unit,
    onDebounceChange: (Long) -> Unit,
    onScanIntervalChange: (Long) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onShowOriginalChange: (Boolean) -> Unit,
    onResetRoiClick: () -> Unit,
    onClearCacheClick: () -> Unit,
    onClearSessionHistoryClick: () -> Unit,
    onSelectSandboxPreset: (SandboxPreset) -> Unit,
    onRunSandboxTest: (Bitmap) -> Unit,
    onClearSandbox: () -> Unit
) {
    val isServiceActive = uiState.telemetry.status == ServiceStatus.RUNNING ||
        uiState.telemetry.status == ServiceStatus.PAUSED
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.GENERAL_AI) }

    BackHandler(enabled = selectedTab != MainTab.GENERAL_AI) {
        selectedTab = MainTab.GENERAL_AI
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = ObsidianSurface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == MainTab.GENERAL_AI,
                    onClick = { selectedTab = MainTab.GENERAL_AI },
                    icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(R.string.tab_general_ai),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.testTag("nav_tab_general_ai"),
                    colors = navBarItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.APPEARANCE,
                    onClick = { selectedTab = MainTab.APPEARANCE },
                    icon = { Icon(Icons.Rounded.Palette, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(R.string.tab_appearance),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.testTag("nav_tab_appearance"),
                    colors = navBarItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.SANDBOX_HISTORY,
                    onClick = { selectedTab = MainTab.SANDBOX_HISTORY },
                    icon = { Icon(Icons.Rounded.SportsEsports, contentDescription = null) },
                    label = {
                        Text(
                            text = stringResource(R.string.tab_sandbox_history),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.testTag("nav_tab_sandbox_history"),
                    colors = navBarItemColors()
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0C1729),
                            ObsidianBg,
                            ObsidianBg
                        )
                    )
                )
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
            ) {
                // Persistent Top Hero Header & Master Control Card (always 1-tap away across tabs)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroHeaderBar(uiState = uiState)
                    HeroMasterControlCard(
                        uiState = uiState,
                        isServiceActive = isServiceActive,
                        onSelectSourceLanguage = onSelectSourceLanguage,
                        onSelectTargetLanguage = onSelectTargetLanguage,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onDownloadModelClick = onDownloadModelClick,
                        onStartCaptureClick = onStartCaptureClick,
                        onStopCaptureClick = onStopCaptureClick
                    )
                }

                // Scrollable Tab Body
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        MainTab.GENERAL_AI -> GeneralAiTabContent(
                            uiState = uiState,
                            isServiceActive = isServiceActive,
                            aiApiKeyDraft = aiApiKeyDraft,
                            aiModelDraft = aiModelDraft,
                            aiEndpointDraft = aiEndpointDraft,
                            onSelectEngineMode = onSelectEngineMode,
                            onDownloadModelClick = onDownloadModelClick,
                            onSelectAiPreset = onSelectAiPreset,
                            onAiApiKeyChange = onAiApiKeyChange,
                            onAiModelChange = onAiModelChange,
                            onAiEndpointChange = onAiEndpointChange,
                            onRefreshAiModelsClick = onRefreshAiModelsClick,
                            onTestAiConnectionClick = onTestAiConnectionClick,
                            onDebounceChange = onDebounceChange,
                            onScanIntervalChange = onScanIntervalChange,
                            onResetRoiClick = onResetRoiClick,
                            onClearCacheClick = onClearCacheClick
                        )
                        MainTab.APPEARANCE -> AppearanceTabContent(
                            uiState = uiState,
                            onSelectDisplayMode = onSelectDisplayMode,
                            onBubbleBehaviorChange = onBubbleBehaviorChange,
                            onBubbleThemeChange = onBubbleThemeChange,
                            onSubtitleTextColorChange = onSubtitleTextColorChange,
                            onFontSizeSpChange = onFontSizeSpChange,
                            onClassicTextFitModeChange = onClassicTextFitModeChange,
                            onClassicBoxWidthChange = onClassicBoxWidthChange,
                            onClassicBoxHeightChange = onClassicBoxHeightChange,
                            onClassicBoxOpacityChange = onClassicBoxOpacityChange,
                            onShowOriginalChange = onShowOriginalChange,
                            onResetClassicBoxLayout = onResetClassicBoxLayout,
                            onOpacityChange = onOpacityChange,
                            onInPlacePaddingXChange = onInPlacePaddingXChange,
                            onInPlacePaddingYChange = onInPlacePaddingYChange,
                            onInPlaceCornerRadiusChange = onInPlaceCornerRadiusChange,
                            onInPlaceOffsetXChange = onInPlaceOffsetXChange,
                            onInPlaceOffsetYChange = onInPlaceOffsetYChange,
                            onResetInPlaceTuning = onResetInPlaceTuning
                        )
                        MainTab.SANDBOX_HISTORY -> SandboxAndHistoryTabContent(
                            uiState = uiState,
                            isServiceActive = isServiceActive,
                            onSelectPreset = onSelectSandboxPreset,
                            onSelectDisplayMode = onSelectDisplayMode,
                            onRunSandboxTest = onRunSandboxTest,
                            onClearSandbox = onClearSandbox,
                            onDownloadModelClick = onDownloadModelClick,
                            onStartCaptureClick = onStartCaptureClick,
                            onStopCaptureClick = onStopCaptureClick,
                            onClearSessionHistoryClick = onClearSessionHistoryClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun navBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = CyanPrimary,
    selectedTextColor = CyanPrimary,
    indicatorColor = CyanContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun HeroHeaderBar(uiState: MainUiState) {
    val statusColor by animateColorAsState(
        targetValue = when (uiState.telemetry.status) {
            ServiceStatus.RUNNING -> EmeraldSuccess
            ServiceStatus.PAUSED -> AmberTertiary
            ServiceStatus.ERROR -> MaterialTheme.colorScheme.error
            ServiceStatus.IDLE -> CyanPrimary
        },
        label = "statusColor"
    )

    val statusText = when {
        uiState.telemetry.status == ServiceStatus.RUNNING &&
            uiState.telemetry.overlayHubStage == OverlayHubStage.BUBBLE_IDLE -> "Balon Hazır"
        uiState.telemetry.status == ServiceStatus.RUNNING -> stringResource(R.string.status_running)
        uiState.telemetry.status == ServiceStatus.PAUSED -> stringResource(R.string.status_paused)
        uiState.telemetry.status == ServiceStatus.ERROR -> uiState.telemetry.errorMessage ?: "Hata"
        else -> stringResource(R.string.status_idle)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CyanContainer,
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Subtitles,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            val engineColor = Color(android.graphics.Color.parseColor(uiState.config.engineMode.accentHex))
            val engineTag = uiState.config.engineMode.formatSourceTag(uiState.config.aiPreset)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = engineColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, engineColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = engineTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = engineColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                val subText = when {
                    uiState.telemetry.status == ServiceStatus.RUNNING &&
                        uiState.telemetry.overlayHubStage == OverlayHubStage.BUBBLE_IDLE ->
                        "Balona dokunup çeviri modunu seçin • ${uiState.config.bubbleTheme.labelTr}"
                    uiState.telemetry.framesProcessed > 0 || uiState.telemetry.framesSkippedSameText > 0 ->
                        "Kare: ${uiState.telemetry.framesProcessed} • Atlanan: ${uiState.telemetry.framesSkippedSameText} • Cache: ${uiState.telemetry.cacheHits}"
                    else -> "${uiState.config.displayMode.shortLabelTr} Görünüm • ${uiState.config.engineMode.fullTitleTr}"
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = statusColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroMasterControlCard(
    uiState: MainUiState,
    isServiceActive: Boolean,
    onSelectSourceLanguage: (SupportedLanguage) -> Unit,
    onSelectTargetLanguage: (SupportedLanguage) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onDownloadModelClick: () -> Unit,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit
) {
    val allPermissionsReady = uiState.hasOverlayPermission && uiState.hasNotificationPermission
    val sourceLang = SupportedLanguage.fromCode(uiState.config.sourceLangCode)
    val targetLang = SupportedLanguage.fromCode(uiState.config.targetLangCode)

    val cardBorderColor by animateColorAsState(
        targetValue = if (isServiceActive) CyanPrimary.copy(alpha = 0.55f) else ObsidianOutline,
        label = "heroBorder"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Inline Quick Language Selector (Source -> Target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LanguageDropdownSelector(
                    label = "Oyun",
                    selected = sourceLang,
                    onSelected = onSelectSourceLanguage,
                    testTag = "source_language_selector",
                    compact = true,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "Hedef dile",
                    tint = CyanPrimary,
                    modifier = Modifier.size(18.dp)
                )

                LanguageDropdownSelector(
                    label = "Altyazı",
                    selected = targetLang,
                    onSelected = onSelectTargetLanguage,
                    testTag = "target_language_selector",
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Compact permission actions ONLY when missing
            if (!allPermissionsReady) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!uiState.hasOverlayPermission) {
                        OutlinedButton(
                            onClick = onRequestOverlayPermission,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("grant_overlay_permission_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AmberTertiary)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Layers,
                                contentDescription = null,
                                tint = AmberTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Üstte Gösterme İzni Ver", color = AmberTertiary)
                        }
                    }
                    if (!uiState.hasNotificationPermission) {
                        OutlinedButton(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("grant_notification_permission_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AmberTertiary)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                tint = AmberTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Bildirim İzni Ver", color = AmberTertiary)
                        }
                    }
                }
            }

            // Inline compact warning only when strictly needed
            val needsOfflinePack = (uiState.config.engineMode == TranslationEngineMode.OFFLINE_LOCAL ||
                uiState.config.engineMode == TranslationEngineMode.HYBRID) &&
                uiState.modelState == ModelDownloadState.NOT_DOWNLOADED
            val needsApiKey = (uiState.config.engineMode == TranslationEngineMode.SMART_AI ||
                uiState.config.engineMode == TranslationEngineMode.HYBRID) &&
                uiState.config.aiPreset.requiresUserApiKey &&
                uiState.config.apiKeyFor(uiState.config.aiPreset).isBlank()

            if (needsOfflinePack) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AmberTertiary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Çevrimdışı dil paketi eksik",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberTertiary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onDownloadModelClick,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("İndir", color = CyanPrimary)
                        }
                    }
                }
            } else if (needsApiKey) {
                WarningBanner("${uiState.config.aiPreset.shortLabelTr} seçili: Genel & AI sekmesinden API anahtarını girin.")
            }

            uiState.telemetry.errorMessage?.takeIf { it.isNotBlank() }?.let { errMsg ->
                WarningBanner(errMsg)
            }

            // Primary Start / Stop CTA
            Button(
                onClick = if (isServiceActive) onStopCaptureClick else onStartCaptureClick,
                enabled = isServiceActive || allPermissionsReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("start_stop_capture_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceActive) MaterialTheme.colorScheme.error else CyanPrimary,
                    contentColor = if (isServiceActive) Color.White else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (isServiceActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceActive) {
                        stringResource(R.string.action_stop_capture)
                    } else {
                        stringResource(R.string.action_start_capture)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
