package com.scanface.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
fun ScanCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun PrimaryButton(text: String, icon: AppIcon? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        if (icon != null) {
            Glyph(icon, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text)
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
fun PhotoThumbnail(record: ScanRecord, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (record.photoPath != null) PhotoImage(record.photoPath, Modifier.fillMaxSize(), "Captured face")
        else Glyph(AppIcon.Face, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary, description = "Sample portrait")
    }
}

@Composable
fun RecordRow(record: ScanRecord, selected: Boolean = false, onClick: () -> Unit) {
    Surface(onClick, shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PhotoThumbnail(record, Modifier.size(52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dateLabel(record.createdAtMillis, true), style = MaterialTheme.typography.labelLarge)
                Text(if (record.isDemo) "Sample result" else when (record.status) {
                    AnalysisStatus.AWAITING_MODEL -> "Awaiting model"
                    AnalysisStatus.COMPLETE -> "Facial-age estimate"
                    AnalysisStatus.FAILED -> "Analysis unavailable"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.age != null) Text(ageLabel(record.age), style = MaterialTheme.typography.titleLarge)
            Glyph(AppIcon.Chevron, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PrivacyNote(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Glyph(AppIcon.Lock, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Photos stay on this device. You control what you save and share.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(modifier.semantics { contentDescription = "Facial-age history. $description" }) {
        Canvas(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 8.dp, vertical = 16.dp)) {
            repeat(3) { index ->
                val y = size.height * index / 2
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val low = values.minOf { it.age!! } - 2
            val high = values.maxOf { it.age!! } + 2
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateLabel(values.first().createdAtMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (values.size > 1) Text(dateLabel(values.last().createdAtMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String, onAction: () -> Unit, secondaryAction: String? = null, onSecondary: () -> Unit = {}) {
    ScanCard(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
            Glyph(AppIcon.Face, Modifier.padding(24.dp).size(48.dp), MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(action, AppIcon.Scan, Modifier.fillMaxWidth(), onClick = onAction)
        if (secondaryAction != null) TextButton(onSecondary, Modifier.align(Alignment.CenterHorizontally)) { Text(secondaryAction) }
    }
}
