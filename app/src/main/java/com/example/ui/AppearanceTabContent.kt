package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.ClassicTextFitMode
import com.example.model.DisplayMode
import com.example.model.FloatingBubbleBehavior
import com.example.model.FloatingBubbleTheme
import com.example.model.SubtitleColorPreset
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import kotlin.math.roundToInt

private val EXTRA_CUSTOM_SWATCHES = listOf(
    "Turuncu" to 0xFFFB923C.toInt(),
    "Lavanta" to 0xFFC4B5FD.toInt(),
    "Mercan" to 0xFFFDA4AF.toInt(),
    "Buz Mavisi" to 0xFF93C5FD.toInt()
)

@Composable
internal fun AppearanceTabContent(
    uiState: MainUiState,
    onSelectDisplayMode: (DisplayMode) -> Unit,
    onBubbleBehaviorChange: (FloatingBubbleBehavior) -> Unit,
    onBubbleThemeChange: (FloatingBubbleTheme) -> Unit,
    onSubtitleTextColorChange: (Int) -> Unit,
    onFontSizeSpChange: (Float) -> Unit,
    onClassicTextFitModeChange: (ClassicTextFitMode) -> Unit,
    onClassicBoxWidthChange: (Int) -> Unit,
    onClassicBoxHeightChange: (Int) -> Unit,
    onClassicBoxOpacityChange: (Float) -> Unit,
    onShowOriginalChange: (Boolean) -> Unit,
    onResetClassicBoxLayout: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onInPlacePaddingXChange: (Float) -> Unit,
    onInPlacePaddingYChange: (Float) -> Unit,
    onInPlaceCornerRadiusChange: (Float) -> Unit,
    onInPlaceOffsetXChange: (Float) -> Unit,
    onInPlaceOffsetYChange: (Float) -> Unit,
    onResetInPlaceTuning: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            FloatingBubbleSettingsCard(
                bubbleBehavior = uiState.config.bubbleBehavior,
                bubbleTheme = uiState.config.bubbleTheme,
                onBubbleBehaviorChange = onBubbleBehaviorChange,
                onBubbleThemeChange = onBubbleThemeChange
            )
        }

        item {
            DisplayModeSelectorCard(
                displayMode = uiState.config.displayMode,
                onSelectDisplayMode = onSelectDisplayMode
            )
        }

        item {
            SubtitleColorAndFontCard(
                subtitleTextColorArgb = uiState.config.subtitleTextColorArgb,
                fontSizeSp = uiState.config.fontSizeSp,
                onSubtitleTextColorChange = onSubtitleTextColorChange,
                onFontSizeSpChange = onFontSizeSpChange
            )
        }

        item {
            if (uiState.config.displayMode == DisplayMode.CLASSIC_BOX) {
                ClassicBoxAppearanceCard(
                    uiState = uiState,
                    onClassicTextFitModeChange = onClassicTextFitModeChange,
                    onClassicBoxWidthChange = onClassicBoxWidthChange,
                    onClassicBoxHeightChange = onClassicBoxHeightChange,
                    onClassicBoxOpacityChange = onClassicBoxOpacityChange,
                    onShowOriginalChange = onShowOriginalChange,
                    onResetClassicBoxLayout = onResetClassicBoxLayout
                )
            } else {
                InPlaceBlurAppearanceCard(
                    uiState = uiState,
                    onOpacityChange = onOpacityChange,
                    onInPlacePaddingXChange = onInPlacePaddingXChange,
                    onInPlacePaddingYChange = onInPlacePaddingYChange,
                    onInPlaceCornerRadiusChange = onInPlaceCornerRadiusChange,
                    onInPlaceOffsetXChange = onInPlaceOffsetXChange,
                    onInPlaceOffsetYChange = onInPlaceOffsetYChange,
                    onResetInPlaceTuning = onResetInPlaceTuning
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun DisplayModeSelectorCard(
    displayMode: DisplayMode,
    onSelectDisplayMode: (DisplayMode) -> Unit
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
                    imageVector = Icons.Rounded.Subtitles,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Görüntüleme Modu",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                DisplayMode.entries.forEachIndexed { index, mode ->
                    val selected = displayMode == mode
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
                text = if (displayMode == DisplayMode.IN_PLACE_BLUR) {
                    "Orijinal yazıyı koyu buzlu maskeyle gizleyip çeviriyi tam üzerine yerleştirir; sahne statikken yanıp sönme olmadan sabit kalır."
                } else {
                    "Çeviriyi ekranın üstünde veya altında köşesinden boyutlandırılabilir kutuda gösterir."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleColorAndFontCard(
    subtitleTextColorArgb: Int,
    fontSizeSp: Float,
    onSubtitleTextColorChange: (Int) -> Unit,
    onFontSizeSpChange: (Float) -> Unit
) {
    val activePreset = SubtitleColorPreset.matchingOrNull(subtitleTextColorArgb)
    var showCustomPanel by rememberSaveable { mutableStateOf(activePreset == null) }
    var hexDraft by remember(subtitleTextColorArgb) {
        mutableStateOf(SubtitleColorPreset.formatHex(subtitleTextColorArgb))
    }
    var hexError by remember { mutableStateOf(false) }

    LaunchedEffect(activePreset) {
        if (activePreset == null) {
            showCustomPanel = true
        }
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Çeviri Yazı Rengi & Boyutu",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = activePreset?.labelTr ?: "Özel (${SubtitleColorPreset.formatHex(subtitleTextColorArgb)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(subtitleTextColorArgb)
                )
            }

            // Live Mini Subtitle Preview Strip showing exact color + dark stroke over game tones
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF0B1424), Color(0xFF1A2A48), Color(0xFF0C1524))
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val previewSp = fontSizeSp.coerceIn(13f, 22f).sp
                Box {
                    Text(
                        text = "Kadim mühür kırılıyor, hazır ol!",
                        style = TextStyle(
                            fontSize = previewSp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xF504080F),
                            drawStyle = Stroke(width = 6f, join = StrokeJoin.Round),
                            shadow = Shadow(
                                color = Color(0xF5000000),
                                offset = Offset(0f, 2f),
                                blurRadius = 6f
                            )
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Kadim mühür kırılıyor, hazır ol!",
                        style = TextStyle(
                            fontSize = previewSp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(subtitleTextColorArgb)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Color Palette Row (5 presets + Custom Color toggle)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SubtitleColorPreset.entries.forEach { preset ->
                    val selected = (subtitleTextColorArgb and 0x00FFFFFF) == (preset.argb and 0x00FFFFFF)
                    ColorSwatchCircle(
                        argb = preset.argb,
                        label = preset.labelTr,
                        selected = selected,
                        testTag = "subtitle_color_${preset.name.lowercase()}",
                        onClick = {
                            hexError = false
                            onSubtitleTextColorChange(preset.argb)
                        }
                    )
                }

                FilterChip(
                    selected = showCustomPanel || activePreset == null,
                    onClick = { showCustomPanel = !showCustomPanel },
                    label = {
                        Text(
                            text = "Özel Renk",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("subtitle_color_custom_chip"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanContainer,
                        selectedLabelColor = CyanPrimary
                    )
                )
            }

            AnimatedVisibility(visible = showCustomPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianSurfaceElevated)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EXTRA_CUSTOM_SWATCHES.forEach { (name, argb) ->
                            val selected = (subtitleTextColorArgb and 0x00FFFFFF) == (argb and 0x00FFFFFF)
                            ColorSwatchCircle(
                                argb = argb,
                                label = name,
                                selected = selected,
                                testTag = "subtitle_color_extra_${name.lowercase()}",
                                onClick = {
                                    hexError = false
                                    onSubtitleTextColorChange(argb)
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = hexDraft,
                            onValueChange = { input ->
                                hexDraft = input.take(9)
                                hexError = false
                            },
                            label = { Text("Özel Hex (#RRGGBB)") },
                            singleLine = true,
                            isError = hexError,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("subtitle_color_hex_input")
                        )
                        OutlinedButton(
                            onClick = {
                                val parsed = SubtitleColorPreset.parseHexColorOrNull(hexDraft)
                                if (parsed != null) {
                                    hexError = false
                                    onSubtitleTextColorChange(parsed)
                                } else {
                                    hexError = true
                                }
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("subtitle_color_hex_apply"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CyanPrimary)
                        ) {
                            Text("Uygula", color = CyanPrimary)
                        }
                    }
                    if (hexError) {
                        Text(
                            text = "Geçerli bir 6 haneli Hex kodu girin (örn. #FDE047).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline)

            SliderSettingRow(
                title = "Altyazı Yazı Boyutu (Hassas)",
                subtitle = "12sp – 32sp arası hem canlı katmana hem Canlı Test önizlemesine uygulanır",
                valueLabel = "${fontSizeSp.roundToInt()} sp",
                value = fontSizeSp,
                valueRange = 12f..32f,
                steps = 19,
                testTag = "font_size_slider",
                onValueChange = { onFontSizeSpChange(it.roundToInt().toFloat()) }
            )
        }
    }
}

@Composable
private fun ColorSwatchCircle(
    argb: Int,
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val swatchColor = Color(argb)
    val checkTint = if (swatchColor.luminance() > 0.5f) Color(0xFF090E17) else Color.White

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.RadioButton,
                onClickLabel = "$label seç",
                onClick = onClick
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = swatchColor,
            border = BorderStroke(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) CyanPrimary else Color.White.copy(alpha = 0.28f)
            )
        ) {
            if (selected) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "$label seçili",
                        tint = checkTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassicBoxAppearanceCard(
    uiState: MainUiState,
    onClassicTextFitModeChange: (ClassicTextFitMode) -> Unit,
    onClassicBoxWidthChange: (Int) -> Unit,
    onClassicBoxHeightChange: (Int) -> Unit,
    onClassicBoxOpacityChange: (Float) -> Unit,
    onShowOriginalChange: (Boolean) -> Unit,
    onResetClassicBoxLayout: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Klasik Kutu Görünüm & Sığdırma",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyanPrimary
                    )
                    Text(
                        text = "Satırlar kutu iç genişliğinin tamamını kullanır",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onResetClassicBoxLayout,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("reset_classic_box_button")
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

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Metin Sığdırma Davranışı",
                    style = MaterialTheme.typography.labelLarge
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ClassicTextFitMode.entries.forEachIndexed { index, fitMode ->
                        val selected = uiState.config.classicTextFitMode == fitMode
                        SegmentedButton(
                            selected = selected,
                            onClick = { onClassicTextFitModeChange(fitMode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ClassicTextFitMode.entries.size
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("classic_fit_mode_${fitMode.name.lowercase()}"),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = CyanContainer,
                                activeContentColor = CyanPrimary,
                                inactiveContainerColor = ObsidianSurfaceElevated,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = fitMode.labelTr,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Text(
                    text = uiState.config.classicTextFitMode.descriptionTr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = ObsidianOutline)

            SliderSettingRow(
                title = "Kutu Arka Plan Görünürlüğü (Opaklık)",
                subtitle = "Yalnızca kutu arka planı ve kenarlığı saydamlaşır; yazı %100 görünür ve konturlu kalır",
                valueLabel = "%${(uiState.config.classicBoxOpacity * 100).roundToInt()}",
                value = uiState.config.classicBoxOpacity,
                valueRange = 0f..1f,
                testTag = "classic_box_opacity_slider",
                onValueChange = onClassicBoxOpacityChange
            )

            SliderSettingRow(
                title = "Kutu Genişliği",
                subtitle = "Metin kutunun tüm iç genişliğini kullanarak satırları düzenler",
                valueLabel = "${uiState.config.classicBoxWidthDp} dp",
                value = uiState.config.classicBoxWidthDp.toFloat(),
                valueRange = 200f..440f,
                steps = 23,
                testTag = "classic_box_width_slider",
                onValueChange = { onClassicBoxWidthChange((it / 10f).roundToInt() * 10) }
            )

            SliderSettingRow(
                title = if (uiState.config.classicTextFitMode == ClassicTextFitMode.WRAP_AND_EXPAND) {
                    "Minimum Kutu Yüksekliği"
                } else {
                    "Maksimum Kutu Yüksekliği"
                },
                subtitle = if (uiState.config.classicTextFitMode == ClassicTextFitMode.WRAP_AND_EXPAND) {
                    "Uzun cümlelerde yükseklik otomatik genişleyerek kaydırmayı önler"
                } else {
                    "Metin bu yüksekliğe sığacak şekilde otomatik küçültülür"
                },
                valueLabel = "${uiState.config.classicBoxHeightDp} dp",
                value = uiState.config.classicBoxHeightDp.toFloat(),
                valueRange = 84f..340f,
                testTag = "classic_box_height_slider",
                onValueChange = { onClassicBoxHeightChange((it / 8f).roundToInt() * 8) }
            )

            HorizontalDivider(color = ObsidianOutline)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
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
                        style = MaterialTheme.typography.bodySmall,
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
private fun InPlaceBlurAppearanceCard(
    uiState: MainUiState,
    onOpacityChange: (Float) -> Unit,
    onInPlacePaddingXChange: (Float) -> Unit,
    onInPlacePaddingYChange: (Float) -> Unit,
    onInPlaceCornerRadiusChange: (Float) -> Unit,
    onInPlaceOffsetXChange: (Float) -> Unit,
    onInPlaceOffsetYChange: (Float) -> Unit,
    onResetInPlaceTuning: () -> Unit
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BlurOn,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Doğal Blur Alanı & Geometri",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanPrimary
                        )
                        Text(
                            text = "Taşan köşeleri ve konum kaymasını milimetrik hizalayın",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = onResetInPlaceTuning,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("reset_in_place_tuning_button")
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
                title = "Doğal Blur Maske Koyuluğu",
                subtitle = "Buzlu arka plan maskesinin koyuluk seviyesi",
                valueLabel = "%${(uiState.config.overlayOpacity * 100).roundToInt()}",
                value = uiState.config.overlayOpacity,
                valueRange = 0.50f..0.95f,
                testTag = "opacity_slider",
                onValueChange = onOpacityChange
            )

            HorizontalDivider(color = ObsidianOutline)

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
    }
}
