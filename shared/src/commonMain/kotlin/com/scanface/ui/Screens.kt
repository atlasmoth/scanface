package com.scanface.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scanface.core.*

@Composable
fun OverviewScreen(state: AppState, wide: Boolean, now: Long, onScan: () -> Unit,
                   onHistory: () -> Unit, onRecord: (ScanRecord) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your face. Your perspective.", style = MaterialTheme.typography.headlineLarge)
            Text("Facial-age research, on your device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.records.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    EmptyState("Start with a face scan",
                        "Capture one clear face for a research facial-age estimate. Analysis runs on your device, and your photos stay here.",
                        "Take your first scan", onScan)
                }
                if (wide) Column(Modifier.weight(1f)) { HowItWorks() }
            }
            if (!wide) HowItWorks()
        } else {
            val latest = state.latest!!
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ScanCard {
                        SectionTitle("Latest scan", "View") { onRecord(latest) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            PhotoThumbnail(latest, Modifier.size(84.dp))
                            Column(Modifier.weight(1f)) {
                                if (latest.age != null) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(ageLabel(latest.age), style = MaterialTheme.typography.displayLarge)
                                        Text(" years", Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("Estimated facial age", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("No estimate", style = MaterialTheme.typography.headlineMedium)
                                    Text(if (latest.status == AnalysisStatus.FAILED) "Analysis unavailable" else "Model unavailable",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(dateLabel(latest.createdAtMillis, true), style = MaterialTheme.typography.bodyMedium)
                        PrimaryButton("New face scan", AppIcon.Scan, Modifier.fillMaxWidth(), onClick = onScan)
                    }
                    if (wide) RecentScans(state.records, onHistory, onRecord)
                }
                if (wide) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HistoryChart(state.records, now)
                    HowItWorks()
                }
            }
            if (!wide) {
                HistoryChart(state.records, now)
                RecentScans(state.records, onHistory, onRecord)
            }
        }
        PrivacyNote()
    }
}

@Composable
private fun RecentScans(records: List<ScanRecord>, onHistory: () -> Unit, onRecord: (ScanRecord) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Recent scans", "View all", onHistory)
        records.take(3).forEach { record -> RecordRow(record) { onRecord(record) } }
    }
}

@Composable
private fun HowItWorks() {
    ScanCard(Modifier.fillMaxWidth()) {
        Text("Make room for perspective", style = MaterialTheme.typography.titleLarge)
        Text("Use even lighting, face the camera and keep a natural expression. Changes between photos can reflect lighting or pose, even when your appearance has not changed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(AppIcon.Info, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            Text("Facial age is an estimate, not a diagnosis or a measure of your health.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryChart(records: List<ScanRecord>, now: Long) {
    var range by rememberSaveable { mutableStateOf("Month") }
    val rangeMillis = if (range == "Month") 30L * 86_400_000 else 365L * 86_400_000
    val filtered = records.filter { it.createdAtMillis >= now - rangeMillis }
    ScanCard(Modifier.fillMaxWidth()) {
        Text("Over time", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Month", "Year").forEach {
                FilterChip(selected = range == it, onClick = { range = it }, label = { Text(it) })
            }
        }
        AgeChart(filtered)
        Text("Facial-age estimates in years", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HistoryScreen(records: List<ScanRecord>, selectedId: String?, wide: Boolean, now: Long, onSelect: (ScanRecord) -> Unit,
                  onScan: () -> Unit, onShare: (ScanRecord?) -> Unit, onDelete: (ScanRecord) -> Unit) {
    if (records.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            EmptyState("A fresh start", "Your saved scans will live here, ready to revisit whenever you like.",
                "New face scan", onScan)
        }
        return
    }
    val selected = records.firstOrNull { it.id == selectedId } ?: records.first()
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(if (wide) 0.42f else 1f).fillMaxHeight(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { HistoryChart(records, now) }
            item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${records.size} saved ${if (records.size == 1) "scan" else "scans"}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ onShare(null) }) { Glyph(AppIcon.Share, description = "Export scan history") }
            }
            }
            items(records, key = { it.id }) { record -> RecordRow(record, wide && selected.id == record.id) { onSelect(record) } }
            item { PrivacyNote(Modifier.padding(top = 16.dp)) }
        }
        if (wide) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(0.58f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer)) {
                ResultScreen(selected, false, false, {}, {}, { onShare(selected) }, { onDelete(selected) })
            }
        }
    }
}

@Composable
fun ResultScreen(record: ScanRecord, pending: Boolean, analyzing: Boolean,
                 onSave: () -> Unit, onRetake: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (pending) "Your scan" else dateLabel(record.createdAtMillis, true), style = MaterialTheme.typography.headlineMedium)
                Text("Stored privately on this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScanCard(Modifier.fillMaxWidth()) {
                val photoAspect = if (record.width > 0 && record.height > 0) record.width.toFloat() / record.height else 1f
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PhotoThumbnail(record, Modifier.heightIn(max = 220.dp).aspectRatio(photoAspect))
                }
                if (analyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Analyzing on this device…", style = MaterialTheme.typography.titleLarge)
                    }
                } else if (record.age != null && record.status == AnalysisStatus.COMPLETE) {
                    Badge("Research estimate")
                    Text("Estimated facial age", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(ageLabel(record.age), style = MaterialTheme.typography.displayLarge)
                        Text(" years", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    if (record.enteredAge != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Entered age", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${record.enteredAge} years", style = MaterialTheme.typography.titleMedium)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Difference", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val difference = record.ageDifference!!
                                Text("${if (difference > 0) "+" else ""}${ageLabel(difference)} years", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                } else {
                    Badge(if (record.status == AnalysisStatus.FAILED) "Analysis unavailable" else "Model unavailable")
                    Text("No estimate was produced", style = MaterialTheme.typography.headlineMedium)
                    Text(record.analysisMessage ?: "This photo could not be analyzed on this device. You can keep the capture or take another photo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pending) {
                PrimaryButton("Save scan", AppIcon.Check, Modifier.fillMaxWidth(), !analyzing, onSave)
                OutlinedButton(onRetake, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("Retake photo") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onShare, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                        Glyph(AppIcon.Share, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp)); Text("Share summary")
                    }
                    IconButton(onDelete, Modifier.size(48.dp)) { Glyph(AppIcon.Delete, tint = MaterialTheme.colorScheme.error, description = "Delete scan") }
                }
            }
            ScanCard {
                Text("About this scan", style = MaterialTheme.typography.titleMedium)
                Text("Photos are processed and stored on this device. Sharing a summary includes scan details, never your photo.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.lens != null && record.width > 0 && record.height > 0) {
                    Text("${record.lens} · ${record.width} × ${record.height}", style = MaterialTheme.typography.labelMedium)
                }
                if (record.status == AnalysisStatus.COMPLETE) {
                    record.modelId?.takeIf { it.isNotBlank() }?.let {
                        Text("Model: $it", style = MaterialTheme.typography.labelMedium)
                    }
                    record.analysisMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Facial-age estimates are for research. They are not a measure of your health, a diagnosis, or a prediction of your lifespan.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SettingsScreen(state: AppState, onTheme: (ThemeMode) -> Unit, onAge: (Int?) -> Boolean,
                   onClear: () -> Unit, onRetryCleanup: () -> Unit) {
    var ageText by rememberSaveable(state.enteredAge) { mutableStateOf(state.enteredAge?.toString() ?: "") }
    var ageError by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    fun openLink(url: String) {
        linkError = null
        runCatching { uriHandler.openUri(url) }.onFailure { linkError = "This device could not open the link." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 700.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScanCard {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                ThemeMode.entries.forEach { theme ->
                    Surface(modifier = Modifier.selectable(selected = state.theme == theme, role = Role.RadioButton, onClick = { onTheme(theme) }),
                        shape = RoundedCornerShape(12.dp),
                        color = if (state.theme == theme) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Glyph(when (theme) { ThemeMode.SYSTEM -> AppIcon.System; ThemeMode.LIGHT -> AppIcon.Sun; ThemeMode.DARK -> AppIcon.Moon })
                            Text(when (theme) { ThemeMode.SYSTEM -> "Use device setting"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }, Modifier.weight(1f))
                            RadioButton(selected = state.theme == theme, onClick = null)
                        }
                    }
                }
            }
            ScanCard {
                Text("A little context", style = MaterialTheme.typography.titleLarge)
                Text("Optionally add your age for comparison in new scans. It does not affect the model's estimate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(ageText, { ageText = it.take(3); ageError = false }, label = { Text("Your age (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = ageError, supportingText = { if (ageError) Text("Enter an age from 1 to 120, or leave blank.") })
                PrimaryButton("Save age", enabled = ageText != (state.enteredAge?.toString() ?: "")) {
                    val age = ageText.toIntOrNull()
                    ageError = ageText.isNotBlank() && (age == null || age !in 1..120)
                    if (!ageError) onAge(age)
                }
            }
            ScanCard {
                Text("Your data", style = MaterialTheme.typography.titleLarge)
                PrivacyNote()
                if (state.cleanupPendingCount > 0) {
                    Badge("Photo deletion pending")
                    Text("${state.cleanupPendingCount} ${if (state.cleanupPendingCount == 1) "photo could" else "photos could"} not be removed. The app will retry when you reopen it, or you can try again now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton("Retry photo deletion", AppIcon.Delete, onClick = onRetryCleanup)
                }
                TextButton(onClear, enabled = state.records.isNotEmpty(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete all saved scans")
                }
            }
            ScanCard {
                Badge("Research use")
                Text("About the model", style = MaterialTheme.typography.titleLarge)
                Text("FAHR-FaceAge · Haugg, Lee and colleagues\nThe Lancet Digital Health, 2026",
                    style = MaterialTheme.typography.titleMedium)
                Text("ScanFace runs facial-age estimation on this device using LiteRT on Android and Core ML on iOS. Photos are not sent to a server for analysis.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This research model estimates age from facial appearance. Its clinical findings come from retrospective cancer studies; it has not been validated as a health assessment for individual app users. Lighting, pose and demographic differences can affect estimates.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Model weights: CC BY-NC-SA 4.0. Noncommercial use only; attribution and share-alike terms apply to redistributed adaptations. Author code: Apache 2.0. ScanFace uses converted weights and native face detection; it is not an official author release.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ openLink("https://doi.org/10.1016/j.landig.2026.101056") }) { Text("Read the study") }
                TextButton({ openLink("https://github.com/AIM-Harvard/FAHR-Face") }) { Text("Author code and model source") }
                TextButton({ openLink("https://creativecommons.org/licenses/by-nc-sa/4.0/") }) { Text("Model weight license") }
                linkError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
