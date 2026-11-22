package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.FloatingBubbleBehavior
import com.example.model.FloatingBubbleTheme
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun FloatingBubbleSettingsCard(
    bubbleBehavior: FloatingBubbleBehavior,
    bubbleTheme: FloatingBubbleTheme,
    onBubbleBehaviorChange: (FloatingBubbleBehavior) -> Unit,
    onBubbleThemeChange: (FloatingBubbleTheme) -> Unit
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
                    imageVector = Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Kontrol Balonu & Kenar Dock",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Mod seçilince balon davranışını ve görünüm temasını seçin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Mod Seçilince Balon Davranışı",
                    style = MaterialTheme.typography.labelLarge
                )
                FloatingBubbleBehavior.entries.forEach { behavior ->
                    val selected = bubbleBehavior == behavior
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) CyanContainer.copy(alpha = 0.52f) else ObsidianSurfaceElevated,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) CyanPrimary else ObsidianOutline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                role = Role.RadioButton,
                                onClickLabel = "${behavior.labelTr} seç",
                                onClick = { onBubbleBehaviorChange(behavior) }
                            )
                            .testTag("bubble_behavior_${behavior.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BehaviorMiniBadge(behavior = behavior, theme = bubbleTheme)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = behavior.labelTr,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = behavior.descriptionTr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Surface(
                                    shape = CircleShape,
                                    color = CyanPrimary,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "${behavior.labelTr} seçili",
                                            tint = Color(0xFF04121E),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = ObsidianOutline)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Balon & Yarım Yuvarlak Teması (5 Tasarım)",
                    style = MaterialTheme.typography.labelLarge
                )
                FloatingBubbleTheme.entries.forEach { theme ->
                    val selected = bubbleTheme == theme
                    val accent = themeAccentColor(theme)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) accent.copy(alpha = 0.14f) else ObsidianSurfaceElevated,
                        border = BorderStroke(
                            width = if (selected) 1.6.dp else 1.dp,
                            color = if (selected) accent else ObsidianOutline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                role = Role.RadioButton,
                                onClickLabel = "${theme.labelTr} temasını seç",
                                onClick = { onBubbleThemeChange(theme) }
                            )
                            .testTag("bubble_theme_${theme.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BubbleAndDockThemePreview(
                                theme = theme,
                                modifier = Modifier.size(width = 80.dp, height = 48.dp)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = theme.labelTr,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = theme.subtitleTr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (selected) {
                                Surface(
                                    shape = CircleShape,
                                    color = accent,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "${theme.labelTr} seçili",
                                            tint = Color(0xFF090E17),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BehaviorMiniBadge(
    behavior: FloatingBubbleBehavior,
    theme: FloatingBubbleTheme
) {
    val accent = themeAccentColor(theme)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A1220)),
        contentAlignment = Alignment.Center
    ) {
        if (behavior == FloatingBubbleBehavior.HIDE_WHEN_ACTIVE) {
            Text(
                text = "X",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = accent
            )
        } else {
            Text(
                text = "<",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accent
            )
        }
    }
}

@Composable
private fun BubbleAndDockThemePreview(
    theme: FloatingBubbleTheme,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val bubbleSpaceW = size.height
        val topRoom = 4.dp.toPx()
        val r = (min(bubbleSpaceW, size.height - topRoom) * 0.42f)
        val cx = bubbleSpaceW * 0.5f
        val cy = topRoom + (size.height - topRoom) * 0.5f

        when (theme) {
            FloatingBubbleTheme.KWAI -> {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFF6B2C), Color(0xFFFF2E7E), Color(0xFFFF8E3C)),
                        start = Offset(cx - r, cy - r),
                        end = Offset(cx + r, cy + r)
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = Color.White,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.6.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFFFACC15),
                    radius = 2.4.dp.toPx(),
                    center = Offset(cx - r * 0.44f, cy - r * 0.42f)
                )
            }

            FloatingBubbleTheme.SADE -> {
                drawCircle(
                    color = Color(0xEB1C2330),
                    radius = r,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = Color(0xA694A3B8),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            FloatingBubbleTheme.NEON -> {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(Color(0xFF00F5FF), Color(0xFFA855F7), Color(0xFF10B981), Color(0xFF00F5FF)),
                        center = Offset(cx, cy)
                    ),
                    radius = r + 1.5.dp.toPx(),
                    center = Offset(cx, cy),
                    alpha = 0.45f,
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFF09101E),
                    radius = r,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(Color(0xFF00F5FF), Color(0xFFA855F7), Color(0xFF10B981), Color(0xFF00F5FF)),
                        center = Offset(cx, cy)
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.8.dp.toPx())
                )
            }

            FloatingBubbleTheme.KEDI -> {
                for (side in listOf(-1f, 1f)) {
                    val ear = Path().apply {
                        moveTo(cx + side * r * 0.28f, cy - r * 0.56f)
                        lineTo(cx + side * r * 0.72f, cy - r * 1.24f)
                        lineTo(cx + side * r * 0.84f, cy - r * 0.22f)
                        close()
                    }
                    drawPath(ear, color = Color(0xFF2E1F38))
                    drawPath(ear, color = Color(0xFFFDA4AF), style = Stroke(width = 1.3.dp.toPx()))

                    val inner = Path().apply {
                        moveTo(cx + side * r * 0.42f, cy - r * 0.54f)
                        lineTo(cx + side * r * 0.67f, cy - r * 1.02f)
                        lineTo(cx + side * r * 0.73f, cy - r * 0.38f)
                        close()
                    }
                    drawPath(inner, color = Color(0xFFFB7185))
                }
                drawCircle(color = Color(0xFF2E1F38), radius = r, center = Offset(cx, cy))
                drawCircle(
                    color = Color(0xFFFDA4AF),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.4.dp.toPx())
                )
                // Tiny paw beans
                drawCircle(color = Color(0xFFFFE4E6), radius = r * 0.19f, center = Offset(cx, cy + r * 0.16f))
                drawCircle(color = Color(0xFFFDA4AF), radius = r * 0.09f, center = Offset(cx - r * 0.22f, cy - r * 0.13f))
                drawCircle(color = Color(0xFFFDA4AF), radius = r * 0.09f, center = Offset(cx, cy - r * 0.23f))
                drawCircle(color = Color(0xFFFDA4AF), radius = r * 0.09f, center = Offset(cx + r * 0.22f, cy - r * 0.13f))
            }

            FloatingBubbleTheme.KAPLUMBAGA -> {
                drawCircle(color = Color(0xFF4D7C0F), radius = r * 0.24f, center = Offset(cx, cy - r * 0.96f))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF65A30D), Color(0xFF14532D)),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
                val hexR = r * 0.46f
                val hexPath = Path().apply {
                    for (i in 0 until 6) {
                        val a = Math.toRadians((60 * i - 30).toDouble())
                        val vx = cx + (hexR * cos(a)).toFloat()
                        val vy = cy + (hexR * sin(a)).toFloat()
                        if (i == 0) moveTo(vx, vy) else lineTo(vx, vy)
                    }
                    close()
                }
                drawPath(hexPath, color = Color(0xB0BEF264), style = Stroke(width = 1.dp.toPx()))
                for (i in 0 until 6) {
                    val a = Math.toRadians((60 * i - 30).toDouble())
                    drawLine(
                        color = Color(0xB0BEF264),
                        start = Offset(cx + (hexR * cos(a)).toFloat(), cy + (hexR * sin(a)).toFloat()),
                        end = Offset(cx + (r * 0.94f * cos(a)).toFloat(), cy + (r * 0.94f * sin(a)).toFloat()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                drawCircle(
                    color = Color(0xFFA3E635),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.6.dp.toPx())
                )
            }
        }

        // Right-edge Half-Circle ("<") Dock preview alongside the bubble
        val dockRight = size.width - 2.dp.toPx()
        val dockLeft = size.width - 24.dp.toPx()
        val dockTop = topRoom + 3.dp.toPx()
        val dockBottom = size.height - 3.dp.toPx()
        val dockDiameter = dockBottom - dockTop
        val dockPath = Path().apply {
            moveTo(dockRight, dockTop)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = dockLeft,
                    top = dockTop,
                    right = dockLeft + dockDiameter,
                    bottom = dockBottom
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false
            )
            lineTo(dockRight, dockBottom)
            close()
        }

        val dockFill = when (theme) {
            FloatingBubbleTheme.KWAI -> Brush.linearGradient(listOf(Color(0xFFFF6B2C), Color(0xFFFF2E7E)))
            FloatingBubbleTheme.SADE -> Brush.linearGradient(listOf(Color(0xFF1C2330), Color(0xFF1C2330)))
            FloatingBubbleTheme.NEON -> Brush.linearGradient(listOf(Color(0xFF060A13), Color(0xFF0D182D)))
            FloatingBubbleTheme.KEDI -> Brush.linearGradient(listOf(Color(0xFF2E1F38), Color(0xFF3D294A)))
            FloatingBubbleTheme.KAPLUMBAGA -> Brush.linearGradient(listOf(Color(0xFF4D7C0F), Color(0xFF14532D)))
        }
        drawPath(dockPath, brush = dockFill)

        val dockBorderColor = themeAccentColor(theme)
        drawPath(dockPath, color = dockBorderColor, style = Stroke(width = 1.4.dp.toPx()))

        // Crisp "<" arrow inside half-circle dock
        val dcx = (dockLeft + dockRight) * 0.52f
        val dcy = (dockTop + dockBottom) * 0.5f
        val arm = 4.2.dp.toPx()
        val chevPath = Path().apply {
            moveTo(dcx + arm * 0.5f, dcy - arm)
            lineTo(dcx - arm * 0.55f, dcy)
            lineTo(dcx + arm * 0.5f, dcy + arm)
        }
        drawPath(
            path = chevPath,
            color = if (theme == FloatingBubbleTheme.KWAI) Color.White else dockBorderColor,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun themeAccentColor(theme: FloatingBubbleTheme): Color = when (theme) {
    FloatingBubbleTheme.KWAI -> Color(0xFFFF4D6D)
    FloatingBubbleTheme.SADE -> Color(0xFFE2E8F0)
    FloatingBubbleTheme.NEON -> Color(0xFF22D3EE)
    FloatingBubbleTheme.KEDI -> Color(0xFFFDA4AF)
    FloatingBubbleTheme.KAPLUMBAGA -> Color(0xFFA3E635)
}
