package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AiModelOption
import com.example.model.AiProviderPreset
import com.example.model.ModelDownloadState
import com.example.model.TranslationEngineMode
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import kotlin.math.roundToInt

@Composable
internal fun GeneralAiTabContent(
    uiState: MainUiState,
    isServiceActive: Boolean,
    aiApiKeyDraft: String,
    aiModelDraft: String,
    aiEndpointDraft: String,
    onSelectEngineMode: (TranslationEngineMode) -> Unit,
    onDownloadModelClick: () -> Unit,
    onSelectAiPreset: (AiProviderPreset) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiEndpointChange: (String) -> Unit,
    onRefreshAiModelsClick: () -> Unit,
    onTestAiConnectionClick: () -> Unit,
    onDebounceChange: (Long) -> Unit,
    onScanIntervalChange: (Long) -> Unit,
    onResetRoiClick: () -> Unit,
    onClearCacheClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            EngineAndModelPackCard(
                uiState = uiState,
                onSelectEngineMode = onSelectEngineMode,
                onDownloadModelClick = onDownloadModelClick
            )
        }

        item {
            AnimatedVisibility(
                visible = uiState.config.engineMode == TranslationEngineMode.SMART_AI ||
                    uiState.config.engineMode == TranslationEngineMode.HYBRID
            ) {
                AiProviderSettingsCard(
                    uiState = uiState,
                    aiApiKeyDraft = aiApiKeyDraft,
                    aiModelDraft = aiModelDraft,
                    aiEndpointDraft = aiEndpointDraft,
                    onSelectEngineMode = onSelectEngineMode,
                    onSelectAiPreset = onSelectAiPreset,
                    onAiApiKeyChange = onAiApiKeyChange,
                    onAiModelChange = onAiModelChange,
                    onAiEndpointChange = onAiEndpointChange,
                    onRefreshAiModelsClick = onRefreshAiModelsClick,
                    onTestAiConnectionClick = onTestAiConnectionClick
                )
            }
        }

        item {
            PerformanceAccordionCard(
                uiState = uiState,
                isServiceActive = isServiceActive,
                onDebounceChange = onDebounceChange,
                onScanIntervalChange = onScanIntervalChange,
                onResetRoiClick = onResetRoiClick,
                onClearCacheClick = onClearCacheClick
            )
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineAndModelPackCard(
    uiState: MainUiState,
    onSelectEngineMode: (TranslationEngineMode) -> Unit,
    onDownloadModelClick: () -> Unit
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Çeviri Motoru",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TranslationEngineMode.entries.forEach { mode ->
                    val selected = uiState.config.engineMode == mode
                    val label = when (mode) {
                        TranslationEngineMode.OFFLINE_LOCAL -> "Yerel (ML Kit)"
                        TranslationEngineMode.HYBRID -> "Hibrit"
                        TranslationEngineMode.SMART_AI -> "AI (Groq/Gemini)"
                        TranslationEngineMode.GOOGLE_FREE -> "Google (Ücretsiz)"
                        TranslationEngineMode.BING_FREE -> "Bing (Ücretsiz)"
                        TranslationEngineMode.BAIDU_FREE -> "Baidu (Çince)"
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectEngineMode(mode) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("engine_mode_${mode.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = when (mode) {
                                TranslationEngineMode.HYBRID -> Color(0xFFA78BFA).copy(alpha = 0.22f)
                                TranslationEngineMode.SMART_AI -> AmberTertiary.copy(alpha = 0.22f)
                                TranslationEngineMode.GOOGLE_FREE,
                                TranslationEngineMode.BING_FREE,
                                TranslationEngineMode.BAIDU_FREE -> CyanPrimary.copy(alpha = 0.22f)
                                TranslationEngineMode.OFFLINE_LOCAL -> CyanContainer
                            },
                            selectedLabelColor = when (mode) {
                                TranslationEngineMode.HYBRID -> Color(0xFFA78BFA)
                                TranslationEngineMode.SMART_AI -> AmberTertiary
                                TranslationEngineMode.GOOGLE_FREE,
                                TranslationEngineMode.BING_FREE,
                                TranslationEngineMode.BAIDU_FREE -> CyanPrimary
                                TranslationEngineMode.OFFLINE_LOCAL -> CyanPrimary
                            }
                        )
                    )
                }
            }

            Text(
                text = when (uiState.config.engineMode) {
                    TranslationEngineMode.HYBRID ->
                        "Sıfır bekleme: 50ms içinde Yerel çeviriyi gösterir, arka planda hızlı AI ile pürüzsüzce düzeltir."
                    TranslationEngineMode.SMART_AI ->
                        "Durumsuz ultra hızlı LLM çevirisi (Groq / Cerebras / Gemini Düşünme=0, max 120 token, %92 önbellek)."
                    TranslationEngineMode.OFFLINE_LOCAL ->
                        "Sıfır internet gecikmesi ve kotasız sınırsız kullanım için cihaz içi ML Kit."
                    TranslationEngineMode.GOOGLE_FREE ->
                        "Google Çeviri (Ücretsiz): API anahtarı gerektirmez; İngilizce ve Çince dahil tüm dillerden hızlı HTTP çevirisi."
                    TranslationEngineMode.BING_FREE ->
                        "Microsoft / Bing Çeviri (Ücretsiz): Hızlı web tabanlı çeviri ucu; otomatik hata durumunda diğer motorlara düşer."
                    TranslationEngineMode.BAIDU_FREE ->
                        "Baidu Çeviri (Ücretsiz): Özellikle Çince oyun metinleri ve altyazılar için optimize edilmiş ücretsiz web motoru."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val isFreeWebSelected = uiState.config.engineMode == TranslationEngineMode.GOOGLE_FREE ||
                uiState.config.engineMode == TranslationEngineMode.BING_FREE ||
                uiState.config.engineMode == TranslationEngineMode.BAIDU_FREE

            if (isFreeWebSelected) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AmberTertiary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Ücretsiz web çeviriciler API anahtarı istemez ancak çok hızlı art arda isteklerde geçici bekleme verebilir; önbellek ve bekleme süresi (debounce) açık tutulmalıdır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }

            HorizontalDivider(color = ObsidianOutline)

            // Compact Offline ML Kit Language Pack Row
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
                        text = "Yerel ve Hibrit anlık önizleme için gereklidir (~30 MB)",
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
                        contentDescription = "Dil paketi hazır",
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiProviderSettingsCard(
    uiState: MainUiState,
    aiApiKeyDraft: String,
    aiModelDraft: String,
    aiEndpointDraft: String,
    onSelectEngineMode: (TranslationEngineMode) -> Unit,
    onSelectAiPreset: (AiProviderPreset) -> Unit,
    onAiApiKeyChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiEndpointChange: (String) -> Unit,
    onRefreshAiModelsClick: () -> Unit,
    onTestAiConnectionClick: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    val activePreset = uiState.config.aiPreset
    val isGeminiPreset = !activePreset.requiresUserApiKey

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ultra Hızlı AI Sağlayıcı Ayarları",
                    style = MaterialTheme.typography.titleSmall,
                    color = AmberTertiary
                )
                if (aiApiKeyDraft.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${activePreset.shortLabelTr} Kayıtlı",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldSuccess
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AiProviderPreset.entries.forEach { preset ->
                    val selected = activePreset == preset
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectAiPreset(preset) },
                        label = {
                            Text(
                                text = preset.shortLabelTr,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("ai_preset_${preset.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberTertiary.copy(alpha = 0.22f),
                            selectedLabelColor = AmberTertiary
                        )
                    )
                }
            }

            val presetInfoText = when (activePreset) {
                AiProviderPreset.GROQ ->
                    "Groq: Ücretsiz katmanda ~150–350ms yanıt. Güncel modeller: openai/gpt-oss-20b ve qwen/qwen3.8-27b."
                AiProviderPreset.CEREBRAS ->
                    "Cerebras: Saniyede 2000+ token hızında sıfıra yakın gecikme (gpt-oss-120b, qwen-3.8-27b)."
                AiProviderPreset.OPENROUTER ->
                    "OpenRouter: Ücretsiz (:free) veya ücretli yüzlerce model tek anahtarla."
                AiProviderPreset.GEMINI -> if (aiApiKeyDraft.isNotBlank()) {
                    "Gemini: Düşünme bütçesi (thinkingBudget=0) kapalı, minimum gecikme (gemini-2.5-flash-lite / 2.5-flash)."
                } else {
                    "Firebase AI Logic hazır. Kendi Gemini API anahtarınızı eklerseniz thinkingBudget=0 ve canlı model listesi aktif olur."
                }
                AiProviderPreset.OPENAI_COMPATIBLE ->
                    "OpenAI uyumlu herhangi bir yerel veya bulut Chat Completions uç noktasını bağlayın."
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = EmeraldSuccess.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.30f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = presetInfoText,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldSuccess
                    )
                }
            }

            OutlinedTextField(
                value = aiApiKeyDraft,
                onValueChange = onAiApiKeyChange,
                label = {
                    Text(
                        when (activePreset) {
                            AiProviderPreset.GROQ -> "Groq API Anahtarı (yalnızca Groq'a özel)"
                            AiProviderPreset.CEREBRAS -> "Cerebras API Anahtarı (yalnızca Cerebras'a özel)"
                            AiProviderPreset.OPENROUTER -> "OpenRouter API Anahtarı (yalnızca OpenRouter'a özel)"
                            AiProviderPreset.GEMINI -> "Gemini API Anahtarı (yalnızca Gemini'a özel)"
                            AiProviderPreset.OPENAI_COMPATIBLE -> "Özel Sağlayıcı API Anahtarı"
                        }
                    )
                },
                placeholder = {
                    Text(
                        when (activePreset) {
                            AiProviderPreset.GROQ -> "gsk_..."
                            AiProviderPreset.CEREBRAS -> "csk-..."
                            AiProviderPreset.OPENROUTER -> "sk-or-v1-..."
                            AiProviderPreset.GEMINI -> "AIza..."
                            AiProviderPreset.OPENAI_COMPATIBLE -> "sk-..."
                        }
                    )
                },
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
                            imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "API anahtarını göster veya gizle"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_api_key_input")
            )

            // Touch-only Model Dropdown + Refresh Button
            val catalogState = uiState.modelCatalogState
            val availableModels = remember(activePreset, catalogState.modelsByPreset, aiModelDraft) {
                val base = catalogState.modelsByPreset[activePreset] ?: activePreset.curatedModels
                if (aiModelDraft.isNotBlank() && base.none { it.id == aiModelDraft }) {
                    listOf(AiModelOption(aiModelDraft, aiModelDraft)) + base
                } else {
                    base
                }
            }
            val selectedOption = availableModels.firstOrNull { it.id == aiModelDraft } ?: availableModels.first()
            var modelMenuExpanded by remember(activePreset) { mutableStateOf(false) }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Model Seçimi (Dokunarak Seç)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .testTag("ai_model_dropdown"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ObsidianOutline),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = selectedOption.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "▼",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberTertiary
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            availableModels.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            fontWeight = if (option.id == aiModelDraft) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (option.id == aiModelDraft) AmberTertiary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        modelMenuExpanded = false
                                        onAiModelChange(option.id)
                                    },
                                    modifier = Modifier.testTag("ai_model_option_${option.id}")
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onRefreshAiModelsClick,
                        enabled = !catalogState.isFetching && aiApiKeyDraft.isNotBlank(),
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("refresh_models_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.55f)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        if (catalogState.isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = AmberTertiary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Modelleri Getir / Yenile",
                                tint = AmberTertiary
                            )
                        }
                    }
                }

                catalogState.statusMessage?.let { okMsg ->
                    Text(text = okMsg, style = MaterialTheme.typography.labelSmall, color = EmeraldSuccess)
                }
                catalogState.errorMessage?.let { errMsg ->
                    Text(text = errMsg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

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

            val canTest = (!activePreset.requiresUserApiKey || aiApiKeyDraft.isNotBlank()) &&
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
                            style = MaterialTheme.typography.bodySmall,
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
                                style = MaterialTheme.typography.bodySmall,
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
                                    Text("Hızlı Yerel Moda Geç (Kotasız)", color = CyanPrimary)
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun PerformanceAccordionCard(
    uiState: MainUiState,
    isServiceActive: Boolean,
    onDebounceChange: (Long) -> Unit,
    onScanIntervalChange: (Long) -> Unit,
    onResetRoiClick: () -> Unit,
    onClearCacheClick: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Surface(
                onClick = { expanded = !expanded },
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("performance_accordion_toggle")
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
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Performans, ROI & Önbellek",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Sabitleme ${uiState.config.debounceMs}ms • Tarama ${uiState.config.scanIntervalMs}ms • Cache ${uiState.cacheCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Daralt" else "Genişlet",
                        tint = CyanPrimary
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = ObsidianOutline)

                    SliderSettingRow(
                        title = "Metin Sabitleme (Debounce)",
                        subtitle = "Harf harf akan yazılar sabitlenince çevirir (AI için 200-350 ms önerilir)",
                        valueLabel = "${uiState.config.debounceMs} ms",
                        value = uiState.config.debounceMs.toFloat(),
                        valueRange = 150f..1000f,
                        testTag = "debounce_slider",
                        onValueChange = { onDebounceChange((it / 50f).roundToInt() * 50L) }
                    )

                    SliderSettingRow(
                        title = "Kare Tarama Aralığı",
                        subtitle = "Doğal Blur modunda sahne statikken en az 650ms pasif kenar kontrolü yapılır; yanıp sönme oluşmaz",
                        valueLabel = "${uiState.config.scanIntervalMs} ms",
                        value = uiState.config.scanIntervalMs.toFloat(),
                        valueRange = 200f..800f,
                        testTag = "scan_interval_slider",
                        onValueChange = { onScanIntervalChange((it / 50f).roundToInt() * 50L) }
                    )

                    HorizontalDivider(color = ObsidianOutline)

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
                                modifier = Modifier.size(18.dp)
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("reset_roi_button")
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("clear_cache_button")
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
    }
}
