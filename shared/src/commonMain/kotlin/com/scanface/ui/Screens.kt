package com.scanface.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scanface.core.*
import com.scanface.platform.AppHaptic
import com.scanface.platform.LocalAppHaptics

@Composable
fun OverviewScreen(state: AppState, wide: Boolean, now: Long, onScan: () -> Unit,
                   onHistory: () -> Unit, onRecord: (ScanRecord) -> Unit) {
    if (state.records.isEmpty()) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val topSpace = (maxHeight * .17f).coerceIn(56.dp, 144.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp).padding(top = topSpace, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                ScanMark()
                Spacer(Modifier.height(36.dp))
                EmptyState("Start with a face scan", "See your estimated facial age.", "Take your first scan", onScan)
            }
        }
        return
    }
    val latest = state.latest!!
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SectionTitle("Latest scan", "View") { onRecord(latest) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            PhotoThumbnail(latest, Modifier.size(if (wide) 132.dp else 104.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (latest.age != null) {
                                    Text(ageLabel(latest.age), style = MaterialTheme.typography.displayLarge)
                                    Text("Estimated facial age", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("No estimate", style = MaterialTheme.typography.headlineMedium)
                                }
                                Text(dateLabel(latest.createdAtMillis, true), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        PrimaryButton("New face scan", AppIcon.Scan, onClick = onScan)
                    }
                    if (wide) RecentScans(state.records, onHistory, onRecord)
                }
                if (wide) Column(Modifier.weight(1f).padding(top = 8.dp)) { HistoryChart(state.records, now) }
            }
            if (!wide) {
                HistoryChart(state.records, now)
                RecentScans(state.records, onHistory, onRecord)
            }
        }
    }
}

@Composable
private fun RecentScans(records: List<ScanRecord>, onHistory: () -> Unit, onRecord: (ScanRecord) -> Unit) {
    Column {
        SectionTitle("Recent scans", "View all", onHistory)
        records.take(3).forEachIndexed { index, record ->
            RecordRow(record) { onRecord(record) }
            if (index < records.take(3).lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        }
    }
}

@Composable
private fun HistoryChart(records: List<ScanRecord>, now: Long) {
    var range by rememberSaveable { mutableStateOf("Month") }
    val haptics = LocalAppHaptics.current
    val rangeMillis = if (range == "Month") 30L * 86_400_000 else 365L * 86_400_000
    val filtered = records.filter { it.createdAtMillis >= now - rangeMillis }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Over time", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            listOf("Month", "Year").forEach { option ->
                Box(Modifier.heightIn(min = 48.dp)
                    .selectable(selected = range == option, role = Role.RadioButton,
                        onClick = { if (range != option) { haptics.perform(AppHaptic.Selection); range = option } })
                    .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(option, style = MaterialTheme.typography.labelMedium,
                        color = if (range == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AgeChart(filtered)
        Text("Facial-age estimates in years", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HistoryScreen(records: List<ScanRecord>, selectedId: String?, wide: Boolean, now: Long, onSelect: (ScanRecord) -> Unit,
                  onScan: () -> Unit, onShare: (ScanRecord?) -> Unit, onDelete: (ScanRecord) -> Unit) {
    val haptics = LocalAppHaptics.current
    if (records.isEmpty()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val topSpace = (maxHeight * .25f).coerceIn(96.dp, 200.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp).padding(top = topSpace, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("No scans yet", "", "New face scan", onScan)
            }
        }
        return
    }
    val selected = records.firstOrNull { it.id == selectedId } ?: records.first()
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(if (wide) .42f else 1f).fillMaxHeight(), contentPadding = PaddingValues(24.dp)) {
            item { HistoryChart(records, now); Spacer(Modifier.height(32.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${records.size} saved ${if (records.size == 1) "scan" else "scans"}", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ haptics.perform(AppHaptic.Light); onShare(null) }) { Glyph(AppIcon.Share, description = "Export scan history") }
                }
            }
            items(records, key = { it.id }) { record ->
                RecordRow(record, wide && selected.id == record.id) { onSelect(record) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        if (wide) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            Box(Modifier.weight(.58f).fillMaxHeight()) {
                ResultScreen(selected, false, false, {}, {}, { onShare(selected) }, { onDelete(selected) })
            }
        }
    }
}

@Composable
fun ResultScreen(record: ScanRecord, pending: Boolean, analyzing: Boolean,
                 onSave: () -> Unit, onRetake: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val haptics = LocalAppHaptics.current
    var detailsExpanded by rememberSaveable(record.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(if (pending) "Your scan" else dateLabel(record.createdAtMillis, true), style = MaterialTheme.typography.headlineMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 560.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        PhotoThumbnail(record, Modifier.weight(.45f).aspectRatio(.85f))
                        Column(Modifier.weight(.55f)) { ScanEstimate(record, analyzing) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PhotoThumbnail(record, Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1.15f))
                        }
                        ScanEstimate(record, analyzing)
                    }
                }
            }
            if (pending) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PrimaryButton("Save scan", AppIcon.Check, Modifier.fillMaxWidth(), !analyzing, onSave)
                    Spacer(Modifier.height(8.dp))
                    TextButton({ haptics.perform(AppHaptic.Warning); onRetake() }, Modifier.heightIn(min = 48.dp)) { Text("Retake photo") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ haptics.perform(AppHaptic.Light); onShare() }, Modifier.heightIn(min = 48.dp)) {
                        Glyph(AppIcon.Share, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp)); Text("Share summary")
                    }
                    IconButton({ haptics.perform(AppHaptic.Warning); onDelete() }, Modifier.size(48.dp)) {
                        Glyph(AppIcon.Delete, tint = MaterialTheme.colorScheme.error, description = "Delete scan")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                TextButton({ haptics.perform(AppHaptic.Selection); detailsExpanded = !detailsExpanded },
                    Modifier.fillMaxWidth().semantics {
                        stateDescription = if (detailsExpanded) "Expanded" else "Collapsed"
                    }, contentPadding = PaddingValues(vertical = 8.dp)) {
                    Text("About this scan", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Glyph(AppIcon.Chevron, Modifier.size(16.dp).rotate(if (detailsExpanded) 90f else 0f), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (detailsExpanded) {
                    if (record.lens != null && record.width > 0 && record.height > 0) {
                        Text("${record.lens} · ${record.width} × ${record.height}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (record.status == AnalysisStatus.COMPLETE) {
                        record.modelId?.takeIf { it.isNotBlank() }?.let {
                            Text("Model: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        record.analysisMessage?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("Facial-age estimates are for research. They are not a measure of your health or a diagnosis.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ScanEstimate(record: ScanRecord, analyzing: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (analyzing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Analyzing your scan…", style = MaterialTheme.typography.titleMedium)
            }
        } else if (record.age != null && record.status == AnalysisStatus.COMPLETE) {
            Text("Estimated facial age", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ageLabel(record.age), style = MaterialTheme.typography.displayLarge)
                Text("years", Modifier.padding(bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (record.enteredAge != null) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Entered age", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${record.enteredAge} years", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Difference", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val difference = record.ageDifference!!
                        Text("${if (difference > 0) "+" else ""}${ageLabel(difference)} years", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } else {
            Badge(if (record.status == AnalysisStatus.FAILED) "Analysis unavailable" else "Model unavailable")
            Text("No estimate this time", style = MaterialTheme.typography.headlineMedium)
            Text(record.analysisMessage ?: "Try another clear, evenly lit photo of one face.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsScreen(state: AppState, onTheme: (ThemeMode) -> Unit, onAge: (Int?) -> Boolean,
                   onClear: () -> Unit, onRetryCleanup: () -> Unit) {
    var ageText by rememberSaveable(state.enteredAge) { mutableStateOf(state.enteredAge?.toString() ?: "") }
    var ageError by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalAppHaptics.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                ThemeMode.entries.forEach { theme ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = state.theme == theme, role = Role.RadioButton, onClick = {
                            if (state.theme != theme) { haptics.perform(AppHaptic.Selection); onTheme(theme) }
                        }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Glyph(when (theme) { ThemeMode.SYSTEM -> AppIcon.System; ThemeMode.LIGHT -> AppIcon.Sun; ThemeMode.DARK -> AppIcon.Moon },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when (theme) { ThemeMode.SYSTEM -> "Use device setting"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }, Modifier.weight(1f))
                        if (state.theme == theme) Glyph(AppIcon.Check, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your age", style = MaterialTheme.typography.titleLarge)
                Text("An optional comparison for new scans.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(ageText, { ageText = it.take(3); ageError = false }, label = { Text("Your age (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp), isError = ageError,
                    supportingText = if (ageError) ({ Text("Enter an age from 1 to 120, or leave blank.") }) else null)
                PrimaryButton("Save age", enabled = ageText != (state.enteredAge?.toString() ?: "")) {
                    val age = ageText.toIntOrNull()
                    ageError = ageText.isNotBlank() && (age == null || age !in 1..120)
                    if (ageError) haptics.perform(AppHaptic.Error) else onAge(age)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved scans", style = MaterialTheme.typography.titleLarge)
                if (state.cleanupPendingCount > 0) {
                    Text("${state.cleanupPendingCount} ${if (state.cleanupPendingCount == 1) "photo is" else "photos are"} waiting to be deleted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ haptics.perform(AppHaptic.Light); onRetryCleanup() }) { Text("Retry photo deletion") }
                }
                TextButton({ haptics.perform(AppHaptic.Warning); onClear() }, enabled = state.records.isNotEmpty(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Glyph(AppIcon.Delete, Modifier.size(20.dp), if (state.records.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                    Spacer(Modifier.width(10.dp)); Text("Delete all saved scans")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
