package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AiProviderPreset
import com.example.model.DisplayMode
import com.example.model.ModelDownloadState
import com.example.model.ServiceStatus
import com.example.model.SubtitleEntry
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
import kotlin.math.roundToInt

private enum class MainTab {
    CONTROLS,
    SANDBOX
}

@Composable
fun MainScreen(
    uiState: MainUiState,
    aiApiKeyDraft: String,
    aiModelDraft: String,
    aiEndpointDraft: String,
    libreEndpointDraft: String,
    libreApiKeyDraft: String,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit,
    onDownloadModelClick: () -> Unit,
    onSelectDisplayMode: (DisplayMode) -> Unit,
    onSelectEngineMode: (TranslationEngineMode) -> Unit,
    onFontSizeSpChange: (Float) -> Unit,
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
    onLibreEndpointChange: (String) -> Unit,
    onLibreApiKeyChange: (String) -> Unit,
    onTestAiConnectionClick: () -> Unit,
    onSelectSourceLanguage: (SupportedLanguage) -> Unit,
    onSelectTargetLanguage: (SupportedLanguage) -> Unit,
    onDebounceChange: (Long) -> Unit,
    onScanIntervalChange: (Long) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onShowOriginalChange: (Boolean) -> Unit,
    onResetRoiClick: () -> Unit,
    onClearCacheClick: () -> Unit,
    onSelectSandboxPreset: (SandboxPreset) -> Unit,
    onRunSandboxTest: (Bitmap) -> Unit,
    onClearSandbox: () -> Unit
) {
    val isServiceActive = uiState.telemetry.status == ServiceStatus.RUNNING ||
        uiState.telemetry.status == ServiceStatus.PAUSED
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.CONTROLS) }

    BackHandler(enabled = selectedTab == MainTab.SANDBOX) {
        selectedTab = MainTab.CONTROLS
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
                    selected = selectedTab == MainTab.CONTROLS,
                    onClick = { selectedTab = MainTab.CONTROLS },
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_controls)) },
                    modifier = Modifier.testTag("nav_tab_controls"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.SANDBOX,
                    onClick = { selectedTab = MainTab.SANDBOX },
                    icon = { Icon(Icons.Rounded.SportsEsports, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_sandbox)) },
                    modifier = Modifier.testTag("nav_tab_sandbox"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        indicatorColor = CyanContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
            if (selectedTab == MainTab.SANDBOX) {
                SandboxTab(
                    uiState = uiState,
                    isServiceActive = isServiceActive,
                    onSelectPreset = onSelectSandboxPreset,
                    onRunSandboxTest = onRunSandboxTest,
                    onClearSandbox = onClearSandbox,
                    onSelectDisplayMode = onSelectDisplayMode,
                    onSelectEngineMode = onSelectEngineMode,
                    onFontSizeSpChange = onFontSizeSpChange,
                    onInPlacePaddingXChange = onInPlacePaddingXChange,
                    onInPlacePaddingYChange = onInPlacePaddingYChange,
                    onInPlaceCornerRadiusChange = onInPlaceCornerRadiusChange,
                    onInPlaceOffsetXChange = onInPlaceOffsetXChange,
                    onInPlaceOffsetYChange = onInPlaceOffsetYChange,
                    onResetInPlaceTuning = onResetInPlaceTuning,
                    onClassicBoxWidthChange = onClassicBoxWidthChange,
                    onClassicBoxHeightChange = onClassicBoxHeightChange,
                    onClassicBoxOpacityChange = onClassicBoxOpacityChange,
                    onResetClassicBoxLayout = onResetClassicBoxLayout,
                    onDownloadModelClick = onDownloadModelClick,
                    onStartCaptureClick = onStartCaptureClick,
                    onStopCaptureClick = onStopCaptureClick
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        HeaderSection(uiState = uiState)
                    }

                    item {
                        LaunchControlCard(
                            uiState = uiState,
                            isServiceActive = isServiceActive,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onStartCaptureClick = onStartCaptureClick,
                            onStopCaptureClick = onStopCaptureClick
                        )
                    }

                    item {
                        DisplayAndEngineModeCard(
                            uiState = uiState,
                            aiApiKeyDraft = aiApiKeyDraft,
                            aiModelDraft = aiModelDraft,
                            aiEndpointDraft = aiEndpointDraft,
                            libreEndpointDraft = libreEndpointDraft,
                            libreApiKeyDraft = libreApiKeyDraft,
                            onSelectDisplayMode = onSelectDisplayMode,
                            onSelectEngineMode = onSelectEngineMode,
                            onFontSizeSpChange = onFontSizeSpChange,
                            onInPlacePaddingXChange = onInPlacePaddingXChange,
                            onInPlacePaddingYChange = onInPlacePaddingYChange,
                            onInPlaceCornerRadiusChange = onInPlaceCornerRadiusChange,
                            onInPlaceOffsetXChange = onInPlaceOffsetXChange,
                            onInPlaceOffsetYChange = onInPlaceOffsetYChange,
                            onResetInPlaceTuning = onResetInPlaceTuning,
                            onClassicBoxWidthChange = onClassicBoxWidthChange,
                            onClassicBoxHeightChange = onClassicBoxHeightChange,
                            onClassicBoxOpacityChange = onClassicBoxOpacityChange,
                            onResetClassicBoxLayout = onResetClassicBoxLayout,
                            onSelectAiPreset = onSelectAiPreset,
                            onAiApiKeyChange = onAiApiKeyChange,
                            onAiModelChange = onAiModelChange,
                            onAiEndpointChange = onAiEndpointChange,
                            onLibreEndpointChange = onLibreEndpointChange,
                            onLibreApiKeyChange = onLibreApiKeyChange,
                            onTestAiConnectionClick = onTestAiConnectionClick
                        )
                    }

                    item {
                        LanguageAndModelCard(
                            uiState = uiState,
                            onSelectSourceLanguage = onSelectSourceLanguage,
                            onSelectTargetLanguage = onSelectTargetLanguage,
                            onDownloadModelClick = onDownloadModelClick
                        )
                    }

                    item {
                        PipelineTuningCard(
                            uiState = uiState,
                            onDebounceChange = onDebounceChange,
                            onScanIntervalChange = onScanIntervalChange,
                            onOpacityChange = onOpacityChange,
                            onShowOriginalChange = onShowOriginalChange
                        )
                    }

                    item {
                        RoiAndCacheCard(
                            uiState = uiState,
                            isServiceActive = isServiceActive,
                            onResetRoiClick = onResetRoiClick,
                            onClearCacheClick = onClearCacheClick
                        )
                    }

                    item {
                        Text(
                            text = "Son Yakalanan Altyazılar",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (uiState.telemetry.recentEntries.isEmpty()) {
                        item {
                            EmptyHistoryCard()
                        }
                    } else {
                        items(uiState.telemetry.recentEntries.asReversed()) { entry ->
                            SubtitleHistoryCard(entry = entry)
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(uiState: MainUiState) {
    val statusColor by animateColorAsState(
        targetValue = when (uiState.telemetry.status) {
            ServiceStatus.RUNNING -> EmeraldSuccess
            ServiceStatus.PAUSED -> AmberTertiary
            ServiceStatus.ERROR -> MaterialTheme.colorScheme.error
            ServiceStatus.IDLE -> CyanPrimary
        },
        label = "statusColor"
    )

    val statusText = when (uiState.telemetry.status) {
        ServiceStatus.RUNNING -> stringResource(R.string.status_running)
        ServiceStatus.PAUSED -> stringResource(R.string.status_paused)
        ServiceStatus.ERROR -> uiState.telemetry.errorMessage ?: "Hata"
        ServiceStatus.IDLE -> stringResource(R.string.status_idle)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CyanContainer,
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Subtitles,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
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

        if (uiState.telemetry.framesProcessed > 0 || uiState.telemetry.framesSkippedSameText > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryChip(
                    label = "Kare: ${uiState.telemetry.framesProcessed}",
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    label = "Atlanan: ${uiState.telemetry.framesSkippedSameText}",
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    label = "Cache Hit: ${uiState.telemetry.cacheHits}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LaunchControlCard(
    uiState: MainUiState,
    isServiceActive: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit
) {
    val allPermissionsReady = uiState.hasOverlayPermission && uiState.hasNotificationPermission

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "İzinler ve Canlı Yakalama",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            PermissionRow(
                icon = Icons.Rounded.Layers,
                title = stringResource(R.string.permission_overlay_title),
                description = stringResource(R.string.permission_overlay_desc),
                granted = uiState.hasOverlayPermission,
                testTag = "grant_overlay_permission_button",
                onGrantClick = onRequestOverlayPermission
            )

            PermissionRow(
                icon = Icons.Rounded.NotificationsActive,
                title = stringResource(R.string.permission_notification_title),
                description = stringResource(R.string.permission_notification_desc),
                granted = uiState.hasNotificationPermission,
                testTag = "grant_notification_permission_button",
                onGrantClick = onRequestNotificationPermission
            )

            if (uiState.config.engineMode == TranslationEngineMode.OFFLINE_LOCAL &&
                uiState.modelState == ModelDownloadState.NOT_DOWNLOADED
            ) {
                WarningBanner("Çevrimdışı çeviri için aşağıdan dil paketini indirmeniz önerilir.")
            } else if (uiState.config.engineMode == TranslationEngineMode.SMART_AI &&
                uiState.config.aiPreset.requiresUserApiKey &&
                uiState.config.aiApiKey.isBlank()
            ) {
                WarningBanner("Özel uç nokta seçili: Aşağıdaki Motor kartından API anahtarı girin.")
            } else if (uiState.config.engineMode == TranslationEngineMode.LIBRETRANSLATE &&
                uiState.config.libreEndpointUrl.isBlank()
            ) {
                WarningBanner("LibreTranslate seçili: Aşağıdaki Motor kartından sunucu adresini girin.")
            }

            uiState.telemetry.errorMessage?.takeIf { it.isNotBlank() }?.let { errMsg ->
                WarningBanner(errMsg)
            }

            Button(
                onClick = if (isServiceActive) onStopCaptureClick else onStartCaptureClick,
                enabled = isServiceActive || allPermissionsReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
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

@Composable
private fun WarningBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmberTertiary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = AmberTertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AmberTertiary
            )
        }
    }
}

@Composable
private fun DisplayAndEngineModeCard(
    uiState: MainUiState,
    aiApiKeyDraft: String,
    aiModelDraft: String,
    aiEndpointDraft: String,
    libreEndpointDraft: String,
    libreApiKeyDraft: String,
    onSelectDisplayMode: (DisplayMode) -> Unit,
    onSelectEngineMode: (TranslationEngineMode) -> Unit,
    onFontSizeSpChange: (Float) -> Unit,
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
    onLibreEndpointChange: (String) -> Unit,
    onLibreApiKeyChange: (String) -> Unit,
    onTestAiConnectionClick: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.BlurOn,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Görünüm & Çeviri Motoru",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Görüntüleme Modu",
                    style = MaterialTheme.typography.labelLarge
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DisplayMode.entries.forEachIndexed { index, mode ->
                        val selected = uiState.config.displayMode == mode
                        SegmentedButton(
                            selected = selected,
                            onClick = { onSelectDisplayMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = DisplayMode.entries.size
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("display_mode_${mode.name.lowercase()}"),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = CyanContainer,
                                activeContentColor = CyanPrimary,
                                inactiveContainerColor = ObsidianSurfaceElevated,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = if (mode == DisplayMode.CLASSIC_BOX) {
                                    stringResource(R.string.display_mode_classic)
                                } else {
                                    stringResource(R.string.display_mode_in_place)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Text(
                    text = if (uiState.config.displayMode == DisplayMode.IN_PLACE_BLUR) {
                        "Orijinal yazıyı koyu buzlu maskeyle gizleyip çeviriyi tam üzerine yerleştirir; ayrı kutu gizlenir ve köşe kontrol butonu görünür."
                    } else {
                        "Çeviriyi ekranın üstünde veya altında köşesinden boyutlandırılabilir koyu kutuda gösterir."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = ObsidianOutline)

            SliderSettingRow(
                title = "Altyazı Yazı Boyutu (Hassas)",
                subtitle = "12sp – 32sp arası hem canlı katmana hem Canlı Test önizlemesine anında uygulanır",
                valueLabel = "${uiState.config.fontSizeSp.roundToInt()} sp",
                value = uiState.config.fontSizeSp,
                valueRange = 12f..32f,
                steps = 19,
                testTag = "font_size_slider",
                onValueChange = { onFontSizeSpChange(it.roundToInt().toFloat()) }
            )

            HorizontalDivider(color = ObsidianOutline)

            if (uiState.config.displayMode == DisplayMode.IN_PLACE_BLUR) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Doğal Blur Alanı İnce Ayarı",
                                style = MaterialTheme.typography.labelLarge,
                                color = CyanPrimary
                            )
                            Text(
                                text = "Taşan köşeleri ve konum kaymasını milimetrik hizalayın",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onResetInPlaceTuning,
                            modifier = Modifier.testTag("reset_in_place_tuning_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sıfırla")
                        }
                    }

                    SliderSettingRow(
                        title = "Yatay Dolgu (Padding X)",
                        subtitle = "Sağ ve sol kenar genişletme miktarı",
                        valueLabel = "${uiState.config.inPlacePaddingXdp.roundToInt()} dp",
                        value = uiState.config.inPlacePaddingXdp,
                        valueRange = -4f..28f,
                        steps = 31,
                        testTag = "in_place_padding_x_slider",
                        onValueChange = { onInPlacePaddingXChange(it.roundToInt().toFloat()) }
                    )
                    SliderSettingRow(
                        title = "Dikey Dolgu (Padding Y)",
                        subtitle = "Üst ve alt kenar genişletme miktarı",
                        valueLabel = "${uiState.config.inPlacePaddingYdp.roundToInt()} dp",
                        value = uiState.config.inPlacePaddingYdp,
                        valueRange = -4f..22f,
                        steps = 25,
                        testTag = "in_place_padding_y_slider",
                        onValueChange = { onInPlacePaddingYChange(it.roundToInt().toFloat()) }
                    )
                    SliderSettingRow(
                        title = "Köşe Yuvarlatma (Radius)",
                        subtitle = "0dp keskin köşe, 28dp yumuşak kapsül",
                        valueLabel = "${uiState.config.inPlaceCornerRadiusDp.roundToInt()} dp",
                        value = uiState.config.inPlaceCornerRadiusDp,
                        valueRange = 0f..28f,
                        steps = 27,
                        testTag = "in_place_corner_radius_slider",
                        onValueChange = { onInPlaceCornerRadiusChange(it.roundToInt().toFloat()) }
                    )
                    SliderSettingRow(
                        title = "Yatay Kaydırma (Offset X)",
                        subtitle = "Blur katmanını sola (-) veya sağa (+) kaydır",
                        valueLabel = "${uiState.config.inPlaceOffsetXdp.roundToInt()} dp",
                        value = uiState.config.inPlaceOffsetXdp,
                        valueRange = -30f..30f,
                        steps = 59,
                        testTag = "in_place_offset_x_slider",
                        onValueChange = { onInPlaceOffsetXChange(it.roundToInt().toFloat()) }
                    )
                    SliderSettingRow(
                        title = "Dikey Kaydırma (Offset Y)",
                        subtitle = "Blur katmanını yukarı (-) veya aşağı (+) kaydır",
                        valueLabel = "${uiState.config.inPlaceOffsetYdp.roundToInt()} dp",
                        value = uiState.config.inPlaceOffsetYdp,
                        valueRange = -30f..30f,
                        steps = 59,
                        testTag = "in_place_offset_y_slider",
                        onValueChange = { onInPlaceOffsetYChange(it.roundToInt().toFloat()) }
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Klasik Kutu Boyutu & Konumu",
                                style = MaterialTheme.typography.labelLarge,
                                color = CyanPrimary
                            )
                            Text(
                                text = "Ekran üstündeki kutuyu sağ alt köşesinden de çekip boyutlandırabilirsiniz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onResetClassicBoxLayout,
                            modifier = Modifier.testTag("reset_classic_box_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sıfırla")
                        }
                    }
                    SliderSettingRow(
                        title = "Kutu Genişliği",
                        subtitle = "Metin satırları kutu genişliğine göre otomatik kaydırılır",
                        valueLabel = "${uiState.config.classicBoxWidthDp} dp",
                        value = uiState.config.classicBoxWidthDp.toFloat(),
                        valueRange = 200f..440f,
                        steps = 23,
                        testTag = "classic_box_width_slider",
                        onValueChange = { onClassicBoxWidthChange((it / 10f).roundToInt() * 10) }
                    )
                    SliderSettingRow(
                        title = "Kutu Yüksekliği",
                        subtitle = "Uzun diyaloglarda kutu içi dikey kaydırma aktif olur",
                        valueLabel = "${uiState.config.classicBoxHeightDp} dp",
                        value = uiState.config.classicBoxHeightDp.toFloat(),
                        valueRange = 84f..340f,
                        steps = 0,
                        testTag = "classic_box_height_slider",
                        onValueChange = { onClassicBoxHeightChange((it / 8f).roundToInt() * 8) }
                    )
                    SliderSettingRow(
                        title = "Kutu Arka Plan Görünürlüğü (Opaklık)",
                        subtitle = "Yalnızca kutu arka planı ve kenarlığı saydamlaşır; yazı %100 görünür ve konturlu kalır",
                        valueLabel = "%${(uiState.config.classicBoxOpacity * 100).roundToInt()}",
                        value = uiState.config.classicBoxOpacity,
                        valueRange = 0f..1f,
                        testTag = "classic_box_opacity_slider",
                        onValueChange = onClassicBoxOpacityChange
                    )
                }
            }

            HorizontalDivider(color = ObsidianOutline)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Çeviri Motoru",
                    style = MaterialTheme.typography.labelLarge
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TranslationEngineMode.entries.forEachIndexed { index, mode ->
                        val selected = uiState.config.engineMode == mode
                        SegmentedButton(
                            selected = selected,
                            onClick = { onSelectEngineMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TranslationEngineMode.entries.size
                            ),
                            icon = {
                                Icon(
                                    imageVector = when (mode) {
                                        TranslationEngineMode.OFFLINE_LOCAL -> Icons.Rounded.OfflineBolt
                                        TranslationEngineMode.SMART_AI -> Icons.Rounded.AutoAwesome
                                        TranslationEngineMode.LIBRETRANSLATE -> Icons.Rounded.Translate
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("engine_mode_${mode.name.lowercase()}"),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = when (mode) {
                                    TranslationEngineMode.SMART_AI -> AmberTertiary.copy(alpha = 0.22f)
                                    TranslationEngineMode.LIBRETRANSLATE -> Color(0xFF34D399).copy(alpha = 0.22f)
                                    TranslationEngineMode.OFFLINE_LOCAL -> CyanContainer
                                },
                                activeContentColor = when (mode) {
                                    TranslationEngineMode.SMART_AI -> AmberTertiary
                                    TranslationEngineMode.LIBRETRANSLATE -> Color(0xFF34D399)
                                    TranslationEngineMode.OFFLINE_LOCAL -> CyanPrimary
                                },
                                inactiveContainerColor = ObsidianSurfaceElevated,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = when (mode) {
                                    TranslationEngineMode.OFFLINE_LOCAL -> stringResource(R.string.engine_mode_offline)
                                    TranslationEngineMode.SMART_AI -> stringResource(R.string.engine_mode_ai)
                                    TranslationEngineMode.LIBRETRANSLATE -> stringResource(R.string.engine_mode_libre)
                                },
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Text(
                    text = when (uiState.config.engineMode) {
                        TranslationEngineMode.SMART_AI ->
                            "Bağlam duyarlı LLM çevirisi (2.4 sn kota koruma aralığı + benzerlik önbelleği ile düşük kota tüketimi)."
                        TranslationEngineMode.LIBRETRANSLATE ->
                            "Açık kaynak LibreTranslate (kendi sunucunuz veya herhangi bir örnek). Toplu satır ve önbellek desteği."
                        TranslationEngineMode.OFFLINE_LOCAL ->
                            "Sıfır internet gecikmesi ve sınırsız kullanım için cihaz içi ML Kit."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = uiState.config.engineMode == TranslationEngineMode.SMART_AI) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = ObsidianOutline)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Sağlayıcı Ayarları",
                            style = MaterialTheme.typography.labelLarge,
                            color = AmberTertiary
                        )
                        val isReadyBadge = uiState.config.aiApiKey.isNotBlank()
                        if (isReadyBadge) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldSuccess,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Anahtar Kaydedildi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldSuccess
                                )
                            }
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AiProviderPreset.entries.forEachIndexed { index, preset ->
                            SegmentedButton(
                                selected = uiState.config.aiPreset == preset,
                                onClick = { onSelectAiPreset(preset) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AiProviderPreset.entries.size
                                ),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("ai_preset_${preset.name.lowercase()}")
                            ) {
                                Text(
                                    text = preset.shortLabelTr,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    val isGeminiPreset = !uiState.config.aiPreset.requiresUserApiKey
                    if (isGeminiPreset) {
                        val hasGeminiKey = aiApiKeyDraft.isNotBlank()
                        val activeModelHint = if (hasGeminiKey) {
                            "Gemini API anahtarı ile doğrudan bağlanılıyor. Kota koruma: 2.4 sn istek aralığı + noktalama filtresi + %92 benzerlik önbelleği aktif."
                        } else {
                            "Firebase AI Logic hazır (google-services.json yapılandırıldı). Kota koruma: 2.4 sn istek aralığı + %92 benzerlik önbelleği aktif; daha yüksek limit için Flash-Lite seçebilirsiniz."
                        }
                        val hintColor = EmeraldSuccess
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = hintColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, hintColor.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = hintColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = activeModelHint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = hintColor
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = aiApiKeyDraft,
                        onValueChange = onAiApiKeyChange,
                        label = {
                            Text(
                                if (isGeminiPreset) "Gemini API Anahtarı (önerilir, anında kaydedilir)"
                                else "API Anahtarı (anında kaydedilir)"
                            )
                        },
                        placeholder = { Text(if (isGeminiPreset) "AIza..." else "gsk_... / sk-...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = "API anahtarını göster veya gizle"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_api_key_input")
                    )

                    OutlinedTextField(
                        value = aiModelDraft,
                        onValueChange = onAiModelChange,
                        label = { Text("Model Kimliği") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_model_input")
                    )

                    if (!isGeminiPreset) {
                        OutlinedTextField(
                            value = aiEndpointDraft,
                            onValueChange = onAiEndpointChange,
                            label = { Text("Chat Completions Uç Noktası") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Uri
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_endpoint_input")
                        )
                    }

                    val canTest = (!uiState.config.aiPreset.requiresUserApiKey || aiApiKeyDraft.isNotBlank()) &&
                        uiState.aiTestState !is AiTestState.Testing
                    Button(
                        onClick = onTestAiConnectionClick,
                        enabled = canTest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_ai_connection_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberTertiary,
                            contentColor = Color(0xFF1F1200)
                        )
                    ) {
                        if (uiState.aiTestState is AiTestState.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1F1200)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Bağlantı Test Ediliyor…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text("Bağlantıyı Test Et", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    when (val test = uiState.aiTestState) {
                        is AiTestState.Success -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldSuccess.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "Test Başarılı: \"${test.sampleOutput}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EmeraldSuccess,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                        is AiTestState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = test.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    if (test.isQuotaError) {
                                        OutlinedButton(
                                            onClick = { onSelectEngineMode(TranslationEngineMode.OFFLINE_LOCAL) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("switch_to_offline_from_quota"),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, CyanPrimary)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.OfflineBolt,
                                                contentDescription = null,
                                                tint = CyanPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Hızlı Yerel Moda Geç (Kotasız)",
                                                color = CyanPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }

            AnimatedVisibility(visible = uiState.config.engineMode == TranslationEngineMode.LIBRETRANSLATE) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = ObsidianOutline)
                    val libreAccent = Color(0xFF34D399)
                    Text(
                        text = "LibreTranslate Ayarları",
                        style = MaterialTheme.typography.labelLarge,
                        color = libreAccent
                    )

                    OutlinedTextField(
                        value = libreEndpointDraft,
                        onValueChange = onLibreEndpointChange,
                        label = { Text("Sunucu Adresi") },
                        placeholder = { Text("https://libretranslate.com veya http://192.168.1.10:5000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("libre_endpoint_input")
                    )

                    OutlinedTextField(
                        value = libreApiKeyDraft,
                        onValueChange = onLibreApiKeyChange,
                        label = { Text("API Anahtarı (kendi sunucunuzda gerekmez)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = "API anahtarını göster veya gizle"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("libre_api_key_input")
                    )

                    Text(
                        text = "libretranslate.com herkese açık örneği API anahtarı ister. Ücretsiz ve anahtarsız kullanmak için LibreTranslate'i kendi bilgisayarınızda/sunucunuzda çalıştırıp adresini girin (ör. docker run -p 5000:5000 libretranslate/libretranslate).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onTestAiConnectionClick,
                        enabled = libreEndpointDraft.isNotBlank() && uiState.aiTestState !is AiTestState.Testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("test_libre_connection_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = libreAccent,
                            contentColor = Color(0xFF04150F)
                        )
                    ) {
                        if (uiState.aiTestState is AiTestState.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF04150F)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("LibreTranslate Test Ediliyor…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text("LibreTranslate Bağlantısını Test Et", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    when (val test = uiState.aiTestState) {
                        is AiTestState.Success -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = EmeraldSuccess.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "LibreTranslate Test Başarılı: \"${test.sampleOutput}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EmeraldSuccess,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                        is AiTestState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = test.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    testTag: String,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (granted) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Surface(
                shape = CircleShape,
                color = EmeraldSuccess.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.permission_granted),
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.permission_granted),
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldSuccess
                    )
                }
            }
        } else {
            OutlinedButton(
                onClick = onGrantClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag(testTag),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CyanPrimary)
            ) {
                Text(text = stringResource(R.string.permission_grant_action))
            }
        }
    }
}

@Composable
private fun LanguageAndModelCard(
    uiState: MainUiState,
    onSelectSourceLanguage: (SupportedLanguage) -> Unit,
    onSelectTargetLanguage: (SupportedLanguage) -> Unit,
    onDownloadModelClick: () -> Unit
) {
    val sourceLang = SupportedLanguage.fromCode(uiState.config.sourceLangCode)
    val targetLang = SupportedLanguage.fromCode(uiState.config.targetLangCode)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "OCR ve Çeviri Dilleri",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LanguageDropdownSelector(
                    label = "Oyun Dili",
                    selected = sourceLang,
                    onSelected = onSelectSourceLanguage,
                    testTag = "source_language_selector",
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )

                LanguageDropdownSelector(
                    label = "Altyazı Dili",
                    selected = targetLang,
                    onSelected = onSelectTargetLanguage,
                    testTag = "target_language_selector",
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = ObsidianOutline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (uiState.modelState) {
                            ModelDownloadState.READY -> "Çevrimdışı Dil Paketi Hazır"
                            ModelDownloadState.DOWNLOADING -> stringResource(R.string.status_downloading_model)
                            ModelDownloadState.ERROR -> "Model Hatası: ${uiState.modelError ?: "Bilinmeyen"}"
                            else -> "Çevrimdışı Dil Paketi İndirilmedi"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when (uiState.modelState) {
                            ModelDownloadState.READY -> EmeraldSuccess
                            ModelDownloadState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = "Hızlı Yerel Mod (ML Kit) için gereklidir",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.modelState == ModelDownloadState.DOWNLOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = CyanPrimary
                    )
                } else if (uiState.modelState != ModelDownloadState.READY) {
                    OutlinedButton(
                        onClick = onDownloadModelClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("download_model_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("İndir")
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Hazır",
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageDropdownSelector(
    label: String,
    selected: SupportedLanguage,
    onSelected: (SupportedLanguage) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(testTag),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ObsidianOutline),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    text = "${selected.shortLabelTr} (${selected.shortCode})",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                SupportedLanguage.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text("${lang.displayNameTr} (${lang.shortCode})") },
                        onClick = {
                            expanded = false
                            onSelected(lang)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineTuningCard(
    uiState: MainUiState,
    onDebounceChange: (Long) -> Unit,
    onScanIntervalChange: (Long) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onShowOriginalChange: (Boolean) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sabitleme ve Performans Ayarları",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            SliderSettingRow(
                title = "Metin Sabitleme (Debounce)",
                subtitle = "Harf harf akan yazılar sabitlenince çevirir",
                valueLabel = "${uiState.config.debounceMs} ms",
                value = uiState.config.debounceMs.toFloat(),
                valueRange = 200f..1000f,
                testTag = "debounce_slider",
                onValueChange = { onDebounceChange((it / 50f).roundToInt() * 50L) }
            )

            SliderSettingRow(
                title = "Kare Tarama Aralığı",
                subtitle = "Düşük aralık daha hızlı, yüksek aralık daha az pil tüketir",
                valueLabel = "${uiState.config.scanIntervalMs} ms",
                value = uiState.config.scanIntervalMs.toFloat(),
                valueRange = 200f..800f,
                testTag = "scan_interval_slider",
                onValueChange = { onScanIntervalChange((it / 50f).roundToInt() * 50L) }
            )

            SliderSettingRow(
                title = "Doğal Blur Maske Koyuluğu",
                subtitle = "Doğal Blur modundaki buzlu arka plan maskesinin koyuluğu",
                valueLabel = "%${(uiState.config.overlayOpacity * 100).roundToInt()}",
                value = uiState.config.overlayOpacity,
                valueRange = 0.50f..0.95f,
                testTag = "opacity_slider",
                onValueChange = onOpacityChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Orijinal Metni Alt Satırda Göster",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "Klasik Kutu modunda kaynak metni alt satırda gösterir",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.config.showOriginalText,
                    onCheckedChange = onShowOriginalChange,
                    modifier = Modifier.testTag("show_original_switch")
                )
            }
        }
    }
}

@Composable
private fun SliderSettingRow(
    title: String,
    subtitle: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = CyanPrimary
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = CyanPrimary,
                activeTrackColor = CyanPrimary
            )
        )
    }
}

@Composable
private fun RoiAndCacheCard(
    uiState: MainUiState,
    isServiceActive: Boolean,
    onResetRoiClick: () -> Unit,
    onClearCacheClick: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CropFree,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Kayıtlı Altyazı Bölgesi (ROI)",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "x=${uiState.roiRect.x}, y=${uiState.roiRect.y} • ${uiState.roiRect.width}×${uiState.roiRect.height} px",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = onResetRoiClick,
                    enabled = !isServiceActive,
                    modifier = Modifier.testTag("reset_roi_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sıfırla")
                }
            }

            HorizontalDivider(color = ObsidianOutline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Yerel Çeviri Önbelleği",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "${uiState.cacheCount} kayıtlı cümle (Room + LRU)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onClearCacheClick,
                    enabled = uiState.cacheCount > 0,
                    modifier = Modifier.testTag("clear_cache_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Temizle")
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Subtitles,
                contentDescription = null,
                tint = CyanPrimary.copy(alpha = 0.7f),
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Henüz yakalanan altyazı yok",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Çeviriyi başlattıktan sonra camgöbeği ROI çerçevesini oyunun diyalog alanına sürükleyip boyutlandırın. Yüzen kontrol panelinden Kutu/Doğal ve Yerel/AI/Libre arasında anlık geçiş yapabilirsiniz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubtitleHistoryCard(entry: SubtitleEntry) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeText = when {
                    entry.fromCache -> "ÖNBELLEK (${entry.engineMode.shortLabelTr})"
                    entry.engineMode == TranslationEngineMode.SMART_AI -> "AKILLI AI"
                    entry.engineMode == TranslationEngineMode.LIBRETRANSLATE -> "LIBRETRANSLATE"
                    else -> "HIZLI YEREL"
                }
                val badgeColor = when {
                    entry.fromCache -> EmeraldSuccess
                    entry.engineMode == TranslationEngineMode.SMART_AI -> AmberTertiary
                    entry.engineMode == TranslationEngineMode.LIBRETRANSLATE -> Color(0xFF34D399)
                    else -> CyanPrimary
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${entry.latencyMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = entry.sourceText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
