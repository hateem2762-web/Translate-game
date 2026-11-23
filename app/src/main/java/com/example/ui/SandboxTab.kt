package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.model.ClassicTextFitMode
import com.example.model.DisplayMode
import com.example.model.InPlaceBlock
import com.example.model.ModelDownloadState
import com.example.model.TranslationEngineMode
import com.example.overlay.InPlaceCanvasRenderer
import com.example.overlay.InPlaceRenderStyle
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated

@Composable
fun SandboxSimulationCard(
    uiState: MainUiState,
    isServiceActive: Boolean,
    onSelectPreset: (SandboxPreset) -> Unit,
    onSelectDisplayMode: (DisplayMode) -> Unit,
    onRunSandboxTest: (Bitmap) -> Unit,
    onClearSandbox: () -> Unit,
    onDownloadModelClick: () -> Unit,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit
) {
    var sceneHostRef by remember { mutableStateOf<SandboxSceneContainer?>(null) }
    val allPermissionsReady = uiState.hasOverlayPermission && uiState.hasNotificationPermission

    val renderStyle = remember(
        uiState.config.fontSizeSp,
        uiState.config.inPlacePaddingXdp,
        uiState.config.inPlacePaddingYdp,
        uiState.config.inPlaceCornerRadiusDp,
        uiState.config.inPlaceOffsetXdp,
        uiState.config.inPlaceOffsetYdp,
        uiState.config.subtitleTextColorArgb,
        uiState.config.inPlaceBlurStyle,
        uiState.config.inPlaceBlurIntensity
    ) {
        InPlaceRenderStyle(
            fontSizeSp = uiState.config.fontSizeSp,
            paddingXdp = uiState.config.inPlacePaddingXdp,
            paddingYdp = uiState.config.inPlacePaddingYdp,
            cornerRadiusDp = uiState.config.inPlaceCornerRadiusDp,
            offsetXdp = uiState.config.inPlaceOffsetXdp,
            offsetYdp = uiState.config.inPlaceOffsetYdp,
            textColorArgb = uiState.config.subtitleTextColorArgb,
            blurStyle = uiState.config.inPlaceBlurStyle,
            blurIntensity = uiState.config.inPlaceBlurIntensity
        )
    }

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
                        imageVector = Icons.Rounded.SportsEsports,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Oyun Sahnesi Simülasyonu",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Görünüm sekmesindeki renk, boyut, sığdırma ve blur ayarlarını oyuna girmeden test edin",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quick Display Mode Switch for instant A/B visual testing
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DisplayMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = uiState.config.displayMode == mode,
                        onClick = { onSelectDisplayMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = DisplayMode.entries.size
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("sandbox_display_mode_${mode.name.lowercase()}"),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = CyanContainer,
                            activeContentColor = CyanPrimary,
                            inactiveContainerColor = ObsidianSurface,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = if (mode == DisplayMode.CLASSIC_BOX) {
                                stringResource(R.string.display_mode_classic)
                            } else {
                                stringResource(R.string.display_mode_in_place)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Preset Dialogue Selector Buttons (English / Chinese)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SandboxPreset.entries.forEachIndexed { index, preset ->
                    val selected = uiState.sandboxState.selectedPreset == preset
                    SegmentedButton(
                        selected = selected,
                        onClick = { onSelectPreset(preset) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = SandboxPreset.entries.size
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("sandbox_preset_${preset.name.lowercase()}"),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = CyanContainer,
                            activeContentColor = CyanPrimary,
                            inactiveContainerColor = ObsidianSurface,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = preset.labelTr,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Simulated Dark Game Viewport + Live Preview Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(275.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF070B14))
            ) {
                AndroidView(
                    factory = { ctx ->
                        SandboxSceneContainer(ctx).also { sceneHostRef = it }
                    },
                    update = { view ->
                        sceneHostRef = view
                        view.updateScene(
                            dialogueText = uiState.sandboxState.selectedPreset.sampleText,
                            inPlaceBlocks = if (uiState.config.displayMode == DisplayMode.IN_PLACE_BLUR) {
                                uiState.sandboxState.inPlaceBlocks
                            } else {
                                emptyList()
                            },
                            style = renderStyle
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Classic Box preview: background & border opacity 0%-100%, text 100% opaque in chosen color + fit mode
                if (uiState.config.displayMode == DisplayMode.CLASSIC_BOX &&
                    uiState.sandboxState.translatedText.isNotBlank()
                ) {
                    ClassicSandboxPreviewBox(
                        uiState = uiState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                    )
                }

                // Preview of the floating circular corner button when In-Place Blur is active
                if (uiState.config.displayMode == DisplayMode.IN_PLACE_BLUR) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(38.dp),
                        shape = CircleShape,
                        color = Color(0xE8090E17),
                        border = BorderStroke(1.5.dp, CyanPrimary.copy(alpha = 0.75f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.BlurOn,
                                contentDescription = "Köşe kontrol butonu önizlemesi",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Primary 1-tap Live OCR + Translation + Blur Test Button
            Button(
                onClick = {
                    val bmp = sceneHostRef?.captureCleanSceneBitmap()
                    if (bmp != null) {
                        onRunSandboxTest(bmp)
                    }
                },
                enabled = !uiState.sandboxState.isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("run_sandbox_test_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (uiState.sandboxState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OCR & Çeviri Çalışıyor…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(imageVector = Icons.Rounded.Translate, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sahneyi Tara ve Canlı Çevir", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Secondary button: launch/stop real MediaProjection ROI overlay
            OutlinedButton(
                onClick = if (isServiceActive) onStopCaptureClick else onStartCaptureClick,
                enabled = isServiceActive || allPermissionsReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sandbox_roi_capture_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isServiceActive) MaterialTheme.colorScheme.error else CyanPrimary.copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    imageVector = if (isServiceActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = if (isServiceActive) MaterialTheme.colorScheme.error else CyanPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isServiceActive) {
                        "Gerçek ROI Yakalamayı Durdur"
                    } else if (allPermissionsReady) {
                        "Gerçek ROI Çerçevesi ile Sahne Üzerinde Dene"
                    } else {
                        "Gerçek ROI için Yukarıdan İzin Verin"
                    },
                    color = if (isServiceActive) MaterialTheme.colorScheme.error else CyanPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if ((uiState.config.engineMode == TranslationEngineMode.OFFLINE_LOCAL ||
                    uiState.config.engineMode == TranslationEngineMode.HYBRID) &&
                uiState.modelState != ModelDownloadState.READY
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberTertiary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = AmberTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Seçili dil paketi henüz indirilmedi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberTertiary
                            )
                        }
                        TextButton(
                            onClick = onDownloadModelClick,
                            enabled = uiState.modelState != ModelDownloadState.DOWNLOADING
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (uiState.modelState == ModelDownloadState.DOWNLOADING) "İniyor…" else "İndir"
                            )
                        }
                    }
                }
            }

            uiState.sandboxState.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }

            if (uiState.sandboxState.recognizedText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianSurface,
                    border = BorderStroke(1.dp, ObsidianOutline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val usedEngine = uiState.sandboxState.resultEngineMode ?: uiState.config.engineMode
                            val usedTag = usedEngine.formatSourceTag(uiState.config.aiPreset)
                            val usedColor = Color(android.graphics.Color.parseColor(usedEngine.accentHex))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = if (uiState.sandboxState.isAiRefining) AmberTertiary else usedColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.sandboxState.isAiRefining) {
                                        "$usedTag (${uiState.sandboxState.latencyMs} ms) • AI İyileştiriyor…"
                                    } else {
                                        "$usedTag • ${uiState.sandboxState.latencyMs} ms"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.sandboxState.isAiRefining) AmberTertiary else usedColor
                                )
                            }
                            TextButton(onClick = onClearSandbox) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sıfırla")
                            }
                        }
                        Text(
                            text = "Kaynak: ${uiState.sandboxState.recognizedText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Çeviri: ${uiState.sandboxState.translatedText}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassicSandboxPreviewBox(
    uiState: MainUiState,
    modifier: Modifier = Modifier
) {
    val boxOpacity = uiState.config.classicBoxOpacity.coerceIn(0f, 1f)
    val fitMode = uiState.config.classicTextFitMode
    val textColor = Color(uiState.config.subtitleTextColorArgb)
    val maxPreviewHeightDp = if (fitMode == ClassicTextFitMode.WRAP_AND_EXPAND) {
        245
    } else {
        uiState.config.classicBoxHeightDp.coerceIn(72, 195)
    }

    Surface(
        modifier = modifier
            .widthIn(max = uiState.config.classicBoxWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = maxPreviewHeightDp.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF090E17).copy(alpha = boxOpacity),
        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = boxOpacity * 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val usedEngine = uiState.sandboxState.resultEngineMode ?: uiState.config.engineMode
            val engineTag = usedEngine.formatSourceTag(uiState.config.aiPreset)
            val engineColor = Color(android.graphics.Color.parseColor(usedEngine.accentHex))
            Text(
                text = if (uiState.sandboxState.isAiRefining) {
                    "$engineTag • AI İYİLEŞTİRİYOR…"
                } else {
                    val modeTag = if (fitMode == ClassicTextFitMode.AUTO_FIT_SHRINK) "OTO-SIĞDIR" else "ESNEK"
                    "$engineTag • KUTU ($modeTag) • ${uiState.sandboxState.latencyMs}ms"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (uiState.sandboxState.isAiRefining) AmberTertiary else engineColor
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val availWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(60)
                val maxTextHeightPx = with(density) { (maxPreviewHeightDp - 42).coerceAtLeast(28).dp.roundToPx() }
                val baseSize = uiState.config.fontSizeSp.coerceIn(11f, 32f)
                val translated = uiState.sandboxState.translatedText

                val effectiveSp = remember(translated, baseSize, fitMode, availWidthPx, maxTextHeightPx) {
                    if (fitMode == ClassicTextFitMode.WRAP_AND_EXPAND) {
                        baseSize
                    } else {
                        var candidate = baseSize
                        val hasExplicitBreak = translated.contains('\n')
                        var foundSingle = false
                        if (!hasExplicitBreak) {
                            while (candidate >= 11f) {
                                val res = textMeasurer.measure(
                                    text = translated,
                                    style = TextStyle(
                                        fontSize = candidate.sp,
                                        lineHeight = (candidate * 1.24f).sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    constraints = Constraints(maxWidth = availWidthPx)
                                )
                                if (res.lineCount <= 1 && res.size.height <= maxTextHeightPx) {
                                    foundSingle = true
                                    break
                                }
                                candidate -= 0.5f
                            }
                        }
                        if (foundSingle) {
                            candidate
                        } else {
                            var multiCandidate = baseSize
                            while (multiCandidate >= 11f) {
                                val res = textMeasurer.measure(
                                    text = translated,
                                    style = TextStyle(
                                        fontSize = multiCandidate.sp,
                                        lineHeight = (multiCandidate * 1.26f).sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    constraints = Constraints(maxWidth = availWidthPx)
                                )
                                if (res.size.height <= maxTextHeightPx) {
                                    break
                                }
                                multiCandidate -= 0.5f
                            }
                            multiCandidate.coerceAtLeast(11f)
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = translated,
                        style = TextStyle(
                            fontSize = effectiveSp.sp,
                            lineHeight = (effectiveSp * 1.28f).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xF504080F),
                            drawStyle = Stroke(width = 6f, join = StrokeJoin.Round),
                            shadow = Shadow(
                                color = Color(0xF5000000),
                                offset = Offset(0f, 2f),
                                blurRadius = 6f
                            )
                        )
                    )
                    Text(
                        text = translated,
                        style = TextStyle(
                            fontSize = effectiveSp.sp,
                            lineHeight = (effectiveSp * 1.28f).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                    )
                }
            }

            if (uiState.config.showOriginalText && uiState.sandboxState.recognizedText.isNotBlank()) {
                Text(
                    text = uiState.sandboxState.recognizedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

/**
 * Custom Android View container holding:
 * 1. [GameBackdropView] - atmospheric dark fantasy game scene + crisp dialogue text
 * 2. [InPlaceSandboxOverlay] - live [InPlaceCanvasRenderer] drawing frosted blur & translated text
 */
@SuppressLint("ViewConstructor")
private class SandboxSceneContainer(context: Context) : FrameLayout(context) {
    private val backdropView = GameBackdropView(context)
    private val inPlaceOverlay = InPlaceSandboxOverlay(context)

    init {
        addView(backdropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inPlaceOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun updateScene(
        dialogueText: String,
        inPlaceBlocks: List<InPlaceBlock>,
        style: InPlaceRenderStyle
    ) {
        backdropView.setDialogue(dialogueText)
        inPlaceOverlay.setBlocks(inPlaceBlocks, style)
    }

    fun captureCleanSceneBitmap(): Bitmap? {
        if (backdropView.width <= 0 || backdropView.height <= 0) return null
        val bmp = Bitmap.createBitmap(backdropView.width, backdropView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        backdropView.draw(canvas)
        return bmp
    }

    private class GameBackdropView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val scaledDensity = resources.displayMetrics.scaledDensity
        private val jakartaFont = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans)
        private val monoFont = ResourcesCompat.getFont(context, R.font.jetbrains_mono)

        private var dialogue: String = SandboxPreset.ENGLISH_RPG.sampleText

        private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#111A2E")
            style = Paint.Style.FILL
        }
        private val runePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(125, 34, 211, 238)
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
        }
        private val hudBarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(190, 9, 14, 23)
        }
        private val hpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#EF4444")
        }
        private val mpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#3B82F6")
        }
        private val hudLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#CBD5E1")
            textSize = 10f * scaledDensity
            typeface = monoFont
        }
        private val bottomScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val dialogueTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#F8FAFC")
            textSize = 16.5f * scaledDensity
            typeface = jakartaFont
            isFakeBoldText = true
            setShadowLayer(4f * density, 0f, 1.5f * density, AndroidColor.BLACK)
        }
        private val dialogueStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(235, 3, 6, 12)
            textSize = 16.5f * scaledDensity
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * density
            typeface = jakartaFont
            isFakeBoldText = true
        }

        private val rectTmp = RectF()
        private val archPath = Path()

        fun setDialogue(text: String) {
            if (dialogue != text) {
                dialogue = text
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    AndroidColor.parseColor("#0B1326"),
                    AndroidColor.parseColor("#182542"),
                    AndroidColor.parseColor("#0B111E")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, skyPaint)

            glowPaint.shader = RadialGradient(
                w * 0.5f, h * 0.42f, w * 0.42f,
                intArrayOf(
                    AndroidColor.argb(110, 34, 211, 238),
                    AndroidColor.argb(55, 245, 158, 11),
                    AndroidColor.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(w * 0.5f, h * 0.42f, w * 0.42f, glowPaint)

            canvas.drawRect(w * 0.08f, h * 0.14f, w * 0.20f, h * 0.88f, pillarPaint)
            canvas.drawRect(w * 0.80f, h * 0.14f, w * 0.92f, h * 0.88f, pillarPaint)
            archPath.reset()
            archPath.moveTo(w * 0.24f, h * 0.72f)
            archPath.lineTo(w * 0.24f, h * 0.28f)
            archPath.quadTo(w * 0.50f, h * 0.08f, w * 0.76f, h * 0.28f)
            archPath.lineTo(w * 0.76f, h * 0.72f)
            canvas.drawPath(archPath, runePaint)

            rectTmp.set(12f * density, 12f * density, 148f * density, 50f * density)
            canvas.drawRoundRect(rectTmp, 10f * density, 10f * density, hudBarBg)
            canvas.drawText("LV.42 ELDEN KNIGHT", 20f * density, 25f * density, hudLabelPaint)
            rectTmp.set(20f * density, 30f * density, 134f * density, 36f * density)
            canvas.drawRoundRect(rectTmp, 3f * density, 3f * density, hpPaint)
            rectTmp.set(20f * density, 39f * density, 108f * density, 44f * density)
            canvas.drawRoundRect(rectTmp, 3f * density, 3f * density, mpPaint)

            bottomScrimPaint.shader = LinearGradient(
                0f, h * 0.48f, 0f, h,
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    AndroidColor.argb(200, 7, 11, 20),
                    AndroidColor.argb(235, 7, 11, 20)
                ),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.48f, w, h, bottomScrimPaint)

            val textPadX = 22f * density
            val availW = (w - textPadX * 2f).toInt().coerceAtLeast(80)
            val fillLayout = StaticLayout.Builder
                .obtain(dialogue, 0, dialogue.length, dialogueTextPaint, availW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(3f * density, 1.05f)
                .setIncludePad(false)
                .build()
            val strokeLayout = StaticLayout.Builder
                .obtain(dialogue, 0, dialogue.length, dialogueStrokePaint, availW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(3f * density, 1.05f)
                .setIncludePad(false)
                .build()

            val textTop = (h - fillLayout.height - 20f * density).coerceAtLeast(h * 0.56f)
            canvas.save()
            canvas.translate(textPadX, textTop)
            strokeLayout.draw(canvas)
            fillLayout.draw(canvas)
            canvas.restore()
        }
    }

    private class InPlaceSandboxOverlay(context: Context) : View(context) {
        private val renderer = InPlaceCanvasRenderer(context)
        private var blocks: List<InPlaceBlock> = emptyList()
        private var style: InPlaceRenderStyle = InPlaceRenderStyle()

        fun setBlocks(newBlocks: List<InPlaceBlock>, newStyle: InPlaceRenderStyle) {
            blocks = newBlocks
            style = newStyle
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (blocks.isEmpty()) return
            renderer.drawBlocks(
                canvas = canvas,
                blocks = blocks,
                style = style,
                canvasWidth = width,
                canvasHeight = height
            )
        }
    }
}
