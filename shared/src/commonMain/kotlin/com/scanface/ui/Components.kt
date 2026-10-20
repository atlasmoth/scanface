package com.scanface.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.scanface.platform.AppHaptic
import com.scanface.platform.LocalAppHaptics
import com.scanface.core.AnalysisStatus
import com.scanface.core.ScanRecord
import com.scanface.platform.PhotoImage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.math.roundToInt

fun dateLabel(epoch: Long, full: Boolean = false): String {
    val date = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[date.month.ordinal]
    return "$month ${date.day}" + if (full) ", ${date.year}" else ""
}

fun ageLabel(age: Double): String = if (age == age.toInt().toDouble()) age.toInt().toString() else ((age * 10).roundToInt() / 10.0).toString()

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(5.dp)) { drawCircle(Color(0xFF729981)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrimaryButton(text: String, icon: AppIcon? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = LocalAppHaptics.current
    Button({ haptics.perform(AppHaptic.Light); onClick() }, modifier.heightIn(min = 54.dp), enabled = enabled,
        shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        if (icon != null) {
            Glyph(icon, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text)
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val haptics = LocalAppHaptics.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton({ haptics.perform(AppHaptic.Light); onAction() }) { Text(action) }
    }
}

@Composable
fun PhotoThumbnail(record: ScanRecord, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (record.photoPath != null) PhotoImage(record.photoPath, Modifier.fillMaxSize(), "Captured face")
        else Glyph(AppIcon.Face, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary, description = "Photo unavailable")
    }
}

@Composable
fun RecordRow(record: ScanRecord, selected: Boolean = false, onClick: () -> Unit) {
    val haptics = LocalAppHaptics.current
    Surface({ haptics.perform(AppHaptic.Selection); onClick() }, shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PhotoThumbnail(record, Modifier.size(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dateLabel(record.createdAtMillis, true), style = MaterialTheme.typography.labelLarge)
                Text(when (record.status) {
                    AnalysisStatus.AWAITING_MODEL -> "Model unavailable"
                    AnalysisStatus.COMPLETE -> "Facial-age estimate"
                    AnalysisStatus.FAILED -> "Analysis unavailable"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.age != null) Text(ageLabel(record.age), style = MaterialTheme.typography.titleLarge)
            Glyph(AppIcon.Chevron, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AgeChart(records: List<ScanRecord>, modifier: Modifier = Modifier) {
    val values = records.filter { it.age != null && it.status == AnalysisStatus.COMPLETE }.sortedBy { it.createdAtMillis }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (values.isEmpty()) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("Your estimates will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val description = values.joinToString { "${dateLabel(it.createdAtMillis)}: ${ageLabel(it.age!!)} years" }
    val low = values.minOf { it.age!! } - 2
    val high = values.maxOf { it.age!! } + 2
    Column(modifier.semantics { contentDescription = "Facial-age history. $description" }) {
        Row {
            Column(Modifier.width(32.dp).height(132.dp).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                listOf(high, (high + low) / 2, low).forEach {
                    Text(ageLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        Canvas(Modifier.weight(1f).height(132.dp).padding(horizontal = 8.dp, vertical = 16.dp)) {
            repeat(3) { index ->
                val y = size.height * index / 2
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val first = values.first().createdAtMillis
            val span = (values.last().createdAtMillis - first).coerceAtLeast(1)
            val points = values.map {
                Offset(if (values.size == 1) size.width / 2 else (it.createdAtMillis - first).toFloat() / span * size.width,
                    size.height * (1 - ((it.age!! - low) / (high - low))).toFloat())
            }
            val line = Path().apply {
                points.forEachIndexed { i, point -> if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
            }
            drawPath(line, primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEach { drawCircle(primary, 4.dp.toPx(), it) }
        }
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateLabel(values.first().createdAtMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (values.size > 1) Text(dateLabel(values.last().createdAtMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A light, decorative viewfinder mark; no container competing with the action. */
@Composable
fun ScanMark(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(92.dp)) {
        val unit = size.minDimension / 92f
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        val corners = Path().apply {
            moveTo(22 * unit, 4 * unit); lineTo(12 * unit, 4 * unit); quadraticTo(4 * unit, 4 * unit, 4 * unit, 12 * unit); lineTo(4 * unit, 22 * unit)
            moveTo(70 * unit, 4 * unit); lineTo(80 * unit, 4 * unit); quadraticTo(88 * unit, 4 * unit, 88 * unit, 12 * unit); lineTo(88 * unit, 22 * unit)
            moveTo(4 * unit, 70 * unit); lineTo(4 * unit, 80 * unit); quadraticTo(4 * unit, 88 * unit, 12 * unit, 88 * unit); lineTo(22 * unit, 88 * unit)
            moveTo(88 * unit, 70 * unit); lineTo(88 * unit, 80 * unit); quadraticTo(88 * unit, 88 * unit, 80 * unit, 88 * unit); lineTo(70 * unit, 88 * unit)
        }
        drawPath(corners, ink.copy(alpha = .55f), style = stroke)
        drawOval(ink, Offset(28 * unit, 20 * unit), Size(36 * unit, 49 * unit), style = stroke)
        drawLine(ink.copy(alpha = .35f), Offset(17 * unit, 46 * unit), Offset(75 * unit, 46 * unit), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(action, onClick = onAction)
    }
}
