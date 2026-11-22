package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DisplayMode
import com.example.model.SubtitleEntry
import com.example.model.TranslationEngineMode
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SandboxAndHistoryTabContent(
    uiState: MainUiState,
    isServiceActive: Boolean,
    onSelectPreset: (SandboxPreset) -> Unit,
    onSelectDisplayMode: (DisplayMode) -> Unit,
    onRunSandboxTest: (Bitmap) -> Unit,
    onClearSandbox: () -> Unit,
    onDownloadModelClick: () -> Unit,
    onStartCaptureClick: () -> Unit,
    onStopCaptureClick: () -> Unit,
    onClearSessionHistoryClick: () -> Unit
) {
    val context = LocalContext.current
    var copiedTimestamp by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(copiedTimestamp) {
        if (copiedTimestamp != null) {
            delay(1400L)
            copiedTimestamp = null
        }
    }

    val reversedHistory = remember(uiState.telemetry.recentEntries) {
        uiState.telemetry.recentEntries.asReversed()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SandboxSimulationCard(
                uiState = uiState,
                isServiceActive = isServiceActive,
                onSelectPreset = onSelectPreset,
                onSelectDisplayMode = onSelectDisplayMode,
                onRunSandboxTest = onRunSandboxTest,
                onClearSandbox = onClearSandbox,
                onDownloadModelClick = onDownloadModelClick,
                onStartCaptureClick = onStartCaptureClick,
                onStopCaptureClick = onStopCaptureClick
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Diyalog Geçmişi (${reversedHistory.size}/30)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                TextButton(
                    onClick = onClearSessionHistoryClick,
                    enabled = reversedHistory.isNotEmpty(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clear_session_history_button")
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

        if (reversedHistory.isEmpty()) {
            item {
                EmptyHistoryCard()
            }
        } else {
            items(
                items = reversedHistory,
                key = { "${it.timestampMs}_${it.sourceText.hashCode()}" }
            ) { entry ->
                SubtitleHistoryCard(
                    entry = entry,
                    isCopied = copiedTimestamp == entry.timestampMs,
                    onCopyClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("SubLens Çeviri", entry.translatedText)
                        )
                        copiedTimestamp = entry.timestampMs
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
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
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "Henüz diyalog kaydı yok",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Çeviri aktifken yakalanan son 30 cümle burada listelenir. Tek dokunuşla herhangi bir çeviriyi panoya kopyalayabilirsiniz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubtitleHistoryCard(
    entry: SubtitleEntry,
    isCopied: Boolean,
    onCopyClick: () -> Unit
) {
    val timeText = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }

    Card(
        onClick = onCopyClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, if (isCopied) EmeraldSuccess.copy(alpha = 0.6f) else ObsidianOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val engineColor = Color(android.graphics.Color.parseColor(entry.engineMode.accentHex))
                val engineTag = entry.engineMode.formatSourceTag()
                val badgeText = if (entry.fromCache) "$engineTag • ÖNBELLEK" else engineTag
                val badgeColor = if (entry.fromCache) EmeraldSuccess else engineColor
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.38f))
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "$timeText • ${entry.latencyMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onCopyClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isCopied) {
                        Surface(color = Color.Transparent) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Kopyalandı",
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Çeviriyi kopyala",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = entry.sourceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
}
