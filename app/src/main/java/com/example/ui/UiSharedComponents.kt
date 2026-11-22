package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.SupportedLanguage
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ObsidianOutline

@Composable
internal fun WarningBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmberTertiary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, AmberTertiary.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                style = MaterialTheme.typography.bodySmall,
                color = AmberTertiary
            )
        }
    }
}

@Composable
internal fun SliderSettingRow(
    title: String,
    subtitle: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
internal fun LanguageDropdownSelector(
    label: String,
    selected: SupportedLanguage,
    onSelected: (SupportedLanguage) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (!compact && label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(testTag),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ObsidianOutline),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (compact && label.isNotBlank()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "${selected.shortLabelTr} (${selected.shortCode})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
