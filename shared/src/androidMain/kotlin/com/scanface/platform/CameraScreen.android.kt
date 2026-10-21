package com.scanface.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanface.core.CapturedPhoto
import com.scanface.ui.AppIcon
import com.scanface.ui.CameraScanOverlay
import com.scanface.ui.Glyph
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun CameraScreen(
    darkTheme: Boolean,
    analyzing: Boolean,
    feedbackEnabled: Boolean,
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalAppHaptics.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val latestAnalyzing by rememberUpdatedState(analyzing)
    var permissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importedPhoto by remember { mutableStateOf<CapturedPhoto?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRequested = true
        permissionGranted = it
        if (!it) haptics.perform(AppHaptic.Warning)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !importing && !latestAnalyzing) {
            scope.launch {
                importing = true
                importError = null
                var photo: CapturedPhoto? = null
                var handedOff = false
                try {
                    withContext(Dispatchers.IO) { photo = importPhoto(context, uri) }
                    importedPhoto = photo
                    haptics.perform(AppHaptic.Success)
                    latestOnCaptured(requireNotNull(photo))
                    handedOff = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    haptics.perform(AppHaptic.Error)
                    importError = "That photo could not be opened. Choose a different image and try again."
                } finally {
                    if (!handedOff) photo?.path?.let { File(it).delete() }
                    importing = false
                }
            }
        }
    }
    val openPicker = {
        if (!analyzing && !importing) {
            haptics.perform(AppHaptic.Light)
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionGranted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    PlatformBackHandler(enabled = true) {
        haptics.perform(AppHaptic.Light)
        onClose()
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (permissionGranted) {
            GrantedCamera(
                analyzing = analyzing,
                feedbackEnabled = feedbackEnabled,
                importedPhoto = importedPhoto,
                importing = importing,
                importError = importError,
                onCaptured = latestOnCaptured,
                onClose = onClose,
                openPicker = openPicker,
            )
        } else if (analyzing && importedPhoto != null) {
            ImportedPhotoAnalysis(requireNotNull(importedPhoto), feedbackEnabled, onClose)
        } else {
            val permanentlyDenied = permissionRequested && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            CameraPermission(
                permanentlyDenied = permanentlyDenied,
                analyzing = analyzing,
                importing = importing,
                error = importError,
                onAllow = {
                    haptics.perform(AppHaptic.Light)
                    if (permanentlyDenied) createPlatformServices().openAppSettings()
                    else permission.launch(Manifest.permission.CAMERA)
                },
                onClose = onClose,
                openPicker = openPicker,
            )
        }
    }
}

@Composable
private fun GrantedCamera(
    analyzing: Boolean,
    feedbackEnabled: Boolean,
    importedPhoto: CapturedPhoto?,
    importing: Boolean,
    importError: String?,
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
    openPicker: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalAppHaptics.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) { PreviewView(context) }
    val controller = remember(context, previewView) {
        CameraController(context, context.findActivity(), previewView)
    }
    val state by controller.state.collectAsState()
    val busy = state.busy || importing || analyzing
    val controlsLocked = busy || state.zoomChanging
    DisposableEffect(controller, lifecycleOwner) {
        controller.start(lifecycleOwner)
        onDispose { controller.close() }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) haptics.perform(AppHaptic.Error)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CameraPreview(
                previewView, state, busy, analyzing, feedbackEnabled, importedPhoto,
                Modifier.fillMaxWidth().weight(1f),
                onClose = { haptics.perform(AppHaptic.Light); onClose() },
                onCapture = {
                    haptics.perform(AppHaptic.Light)
                    controller.takePhoto {
                        haptics.perform(AppHaptic.Success)
                        onCaptured(it)
                    }
                },
                onFlip = { haptics.perform(AppHaptic.Selection); controller.flip() },
                onFlash = { haptics.perform(AppHaptic.Selection); controller.cycleFlash() },
            )
            TextButton(onClick = openPicker, enabled = !controlsLocked, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (importing) "Preparing photo…" else "Choose an existing photo")
            }
            val error = importError ?: state.error
            if (error != null) {
                Text(error, Modifier.widthIn(max = 500.dp).padding(bottom = 8.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                }, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (!state.ready) TextButton(onClick = {
                    haptics.perform(AppHaptic.Light)
                    controller.start(lifecycleOwner)
                }, enabled = !controlsLocked) { Text("Try camera again") }
            }
        }
        if (state.screenFlashVisible) {
            Box(Modifier.fillMaxSize().background(Color.White).semantics {
                contentDescription = "Screen flash"
            })
        }
    }
}

@Composable
private fun CameraPreview(
    preview: PreviewView,
    state: CameraUiState,
    busy: Boolean,
    analyzing: Boolean,
    feedbackEnabled: Boolean,
    importedPhoto: CapturedPhoto?,
    modifier: Modifier,
    onClose: () -> Unit,
    onCapture: () -> Unit,
    onFlip: () -> Unit,
    onFlash: () -> Unit,
) {
    val controlsEnabled = state.ready && !busy && !state.zoomChanging
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF101414))) {
        AndroidView(factory = { preview }, update = { it.isEnabled = !busy }, modifier = Modifier.fillMaxSize())
        if (analyzing && importedPhoto != null) ImportedPhotoPreview(importedPhoto, Modifier.matchParentSize())
        Box(Modifier.fillMaxWidth().height(110.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .42f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f)))))
        CameraScanOverlay(analyzing, feedbackEnabled, Modifier.matchParentSize().padding(top = 60.dp, bottom = 160.dp))
        if (!state.ready && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else if (!state.ready) {
            Text("Camera unavailable", Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.White, textAlign = TextAlign.Center)
        }
        CameraCircleButton("Close camera", true, Modifier.align(Alignment.TopStart).padding(12.dp), onClose) {
            Glyph(AppIcon.Close, tint = Color.White)
        }
        AnimatedVisibility(
            visible = state.zoomReadoutVisible && !analyzing,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Text(formatZoom(state.requestedZoom), color = CameraAccent,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
        if (analyzing) Text("Analyzing your scan…",
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = 126.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 24.dp)
            .widthIn(max = 360.dp).fillMaxWidth().height(82.dp)) {
            CameraCircleButton(
                description = if (state.front) "Switch to rear camera" else "Switch to front camera",
                enabled = state.canFlip && controlsEnabled,
                modifier = Modifier.align(Alignment.CenterStart), onClick = onFlip,
            ) { FlipGlyph() }
            ShutterButton(controlsEnabled, busy && !analyzing, Modifier.align(Alignment.Center), onCapture)
            CameraCircleButton(
                description = if (state.flashes.size > 1) "${state.flash.label}. Change flash mode." else "Flash unavailable on this camera",
                enabled = controlsEnabled && state.flashes.size > 1,
                modifier = Modifier.align(Alignment.CenterEnd), onClick = onFlash,
            ) { FlashGlyph(state.flash) }
        }
    }
}

@Composable
private fun ImportedPhotoPreview(photo: CapturedPhoto, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFF101414)), contentAlignment = Alignment.Center) {
        val aspect = photo.width.toFloat() / photo.height
        PhotoImage(photo.path, Modifier.width(minOf(maxWidth, maxHeight * aspect)).aspectRatio(aspect), "Selected photo")
    }
}

@Composable
private fun ImportedPhotoAnalysis(photo: CapturedPhoto, feedbackEnabled: Boolean, onClose: () -> Unit) {
    val haptics = LocalAppHaptics.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp).padding(top = 12.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))) {
            ImportedPhotoPreview(photo, Modifier.matchParentSize())
            CameraScanOverlay(true, feedbackEnabled, Modifier.matchParentSize().padding(top = 60.dp, bottom = 80.dp))
            CameraCircleButton("Close camera", true, Modifier.align(Alignment.TopStart).padding(12.dp),
                { haptics.perform(AppHaptic.Light); onClose() }) { Glyph(AppIcon.Close, tint = Color.White) }
            Text("Analyzing your scan…", Modifier.align(Alignment.BottomCenter).padding(24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
                color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        TextButton({}, enabled = false, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 56.dp)) {
            Text("Choose an existing photo")
        }
    }
}

private val CameraAccent = Color(0xFFFFD679)

@Composable
private fun CameraCircleButton(
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .30f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }
        .alpha(if (enabled) 1f else .38f), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val innerScale by animateFloatAsState(if (pressed) .86f else 1f, label = "shutterPress")
    Box(modifier.size(82.dp)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = if (busy) "Preparing photo" else "Capture photo" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = if (enabled || busy) 1f else .35f), size.minDimension / 2 - 3.dp.toPx(),
                style = Stroke(3.dp.toPx()))
            drawCircle(Color.White.copy(alpha = if (enabled) 1f else .35f), (size.minDimension / 2 - 9.dp.toPx()) * innerScale)
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
    }
}

@Composable
private fun FlashGlyph(flash: CameraFlash) {
    val active = flash != CameraFlash.OFF
    Canvas(Modifier.size(23.dp)) {
        val tint = if (active) CameraAccent else Color.White
        val sx = size.width / 24f
        val sy = size.height / 24f
        val bolt = Path().apply {
            moveTo(14f * sx, 2f * sy); lineTo(5f * sx, 13f * sy)
            lineTo(11f * sx, 13f * sy); lineTo(9f * sx, 22f * sy)
            lineTo(20f * sx, 10f * sy); lineTo(14f * sx, 10f * sy); close()
        }
        drawPath(bolt, tint, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
        if (!active) drawLine(tint, Offset(3f * sx, 3f * sy), Offset(21f * sx, 21f * sy), 1.7.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun FlipGlyph() {
    Canvas(Modifier.size(25.dp)) {
        val inset = size.width * .16f
        val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White, 205f, 135f, false, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = stroke)
        drawArc(Color.White, 25f, 135f, false, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = stroke)
        val arrow = Path().apply {
            moveTo(size.width * .65f, size.height * .10f); lineTo(size.width * .84f, size.height * .29f); lineTo(size.width * .84f, size.height * .08f)
            moveTo(size.width * .35f, size.height * .90f); lineTo(size.width * .16f, size.height * .71f); lineTo(size.width * .16f, size.height * .92f)
        }
        drawPath(arrow, Color.White, style = stroke)
    }
}

@Composable
private fun CameraPermission(
    permanentlyDenied: Boolean,
    analyzing: Boolean,
    importing: Boolean,
    error: String?,
    onAllow: () -> Unit,
    onClose: () -> Unit,
    openPicker: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        IconButton(onClick = { haptics.perform(AppHaptic.Light); onClose() },
            modifier = Modifier.semantics { contentDescription = "Close camera" }) { Glyph(AppIcon.Close) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val accent = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(88.dp).padding(18.dp)) {
                    drawRoundRect(accent, topLeft = Offset(0f, size.height * 0.16f), size = Size(size.width, size.height * 0.70f), style = Stroke(3.dp.toPx()))
                    drawCircle(accent, size.width * 0.2f, Offset(size.width / 2, size.height * 0.5f), style = Stroke(3.dp.toPx()))
                }
                Spacer(Modifier.height(24.dp))
                Text("Let's get you in the frame", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    if (permanentlyDenied) "Enable camera access in Settings to take a photo. You can also choose an existing photo."
                    else "Allow camera access to take a photo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )
                Button(onClick = onAllow, enabled = !importing && !analyzing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (permanentlyDenied) "Open Settings" else "Allow camera")
                }
                TextButton(onClick = openPicker, enabled = !importing && !analyzing) {
                    Text(if (importing) "Preparing photo…" else "Choose an existing photo")
                }
                if (importing) CircularProgressIndicator(Modifier.size(24.dp))
                if (analyzing) Text("Analyzing your scan…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (error != null) Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun importPhoto(context: Context, uri: Uri): CapturedPhoto {
    val directory = File(context.filesDir, "captures")
    check(directory.exists() || directory.mkdirs()) { "Photo storage unavailable" }
    val id = UUID.randomUUID().toString()
    val raw = File(directory, "$id-import")
    val destination = File(directory, "$id.jpg")
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            raw.outputStream().use { output -> input.copyTo(output) }
        }
        val (width, height) = normalizePhoto(raw, destination)
        return CapturedPhoto(
            destination.absolutePath, width, height, "Photo library", false, System.currentTimeMillis(),
        )
    } catch (error: Throwable) {
        destination.delete()
        throw error
    } finally {
        raw.delete()
    }
}
