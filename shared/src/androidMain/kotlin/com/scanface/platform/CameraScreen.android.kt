package com.scanface.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun CameraScreen(
    darkTheme: Boolean,
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    var permissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRequested = true
        permissionGranted = it
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !importing) {
            scope.launch {
                importing = true
                importError = null
                var photo: CapturedPhoto? = null
                var handedOff = false
                try {
                    withContext(Dispatchers.IO) { photo = importPhoto(context, uri) }
                    latestOnCaptured(requireNotNull(photo))
                    handedOff = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    importError = "That photo could not be opened. Choose a different image and try again."
                } finally {
                    if (!handedOff) photo?.path?.let { File(it).delete() }
                    importing = false
                }
            }
        }
    }
    val openPicker = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionGranted = context.hasCameraPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    PlatformBackHandler(enabled = true, onBack = onClose)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (permissionGranted) {
            GrantedCamera(
                importing = importing,
                importError = importError,
                onCaptured = latestOnCaptured,
                onClose = onClose,
                openPicker = openPicker,
            )
        } else {
            val permanentlyDenied = permissionRequested && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            CameraPermission(
                permanentlyDenied = permanentlyDenied,
                importing = importing,
                error = importError,
                onAllow = {
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
    importing: Boolean,
    importError: String?,
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
    openPicker: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) { PreviewView(context) }
    val controller = remember(context, previewView) {
        CameraController(context, context.findActivity(), previewView)
    }
    val state by controller.state.collectAsState()
    val busy = state.busy || importing
    DisposableEffect(controller, lifecycleOwner) {
        controller.start(lifecycleOwner)
        onDispose { controller.close() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close camera" }) {
                    Text("Close")
                }
                Text("New scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = controller::cycleFlash,
                    enabled = state.ready && !busy && state.flashes.size > 1,
                    modifier = Modifier.semantics {
                        contentDescription = if (state.flashes.size > 1) "${state.flash.label}. Change flash mode."
                        else "Flash unavailable on this camera"
                    },
                ) {
                    Text(if (state.flashes.size > 1) state.flash.label else "No flash")
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp)) {
                val sideBySide = maxWidth >= 700.dp || (maxWidth > maxHeight * 1.25f && maxWidth >= 500.dp)
                if (sideBySide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        CameraPreview(
                            previewView,
                            state,
                            Modifier.weight(1.25f).fillMaxHeight(),
                        )
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CameraGuidance()
                            Spacer(Modifier.height(24.dp))
                            CameraControls(
                                state, busy, importError,
                                onCapture = { controller.takePhoto(onCaptured) },
                                onFlip = controller::flip,
                                onZoom = controller::setZoom,
                                onRetry = { controller.start(lifecycleOwner) },
                                openPicker = openPicker,
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "A moment for you",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Face the camera in soft, even light.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                            textAlign = TextAlign.Center,
                        )
                        CameraPreview(previewView, state, Modifier.fillMaxWidth().weight(1f))
                        Spacer(Modifier.height(12.dp))
                        CameraControls(
                            state, busy, importError,
                            onCapture = { controller.takePhoto(onCaptured) },
                            onFlip = controller::flip,
                            onZoom = controller::setZoom,
                            onRetry = { controller.start(lifecycleOwner) },
                            openPicker = openPicker,
                        )
                    }
                }
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
private fun CameraPreview(preview: PreviewView, state: CameraUiState, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF1A2424))) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        if (state.ready) {
            Canvas(Modifier.fillMaxSize()) {
                val ovalWidth = minOf(size.width * 0.64f, size.height * 0.64f)
                val ovalHeight = minOf(size.height * 0.67f, ovalWidth * 1.42f)
                val start = Offset((size.width - ovalWidth) / 2, (size.height - ovalHeight) / 2)
                drawOval(
                    Color.White.copy(alpha = 0.82f),
                    start,
                    Size(ovalWidth, ovalHeight),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))),
                )
                val length = 17.dp.toPx()
                val outer = 18.dp.toPx()
                listOf(
                    Offset(outer, outer) to Offset(1f, 1f),
                    Offset(size.width - outer, outer) to Offset(-1f, 1f),
                    Offset(outer, size.height - outer) to Offset(1f, -1f),
                    Offset(size.width - outer, size.height - outer) to Offset(-1f, -1f),
                ).forEach { (point, direction) ->
                    drawLine(Color.White, point, point + Offset(length * direction.x, 0f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, point, point + Offset(0f, length * direction.y), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            PreviewPill(state.cameraLabel, Alignment.TopCenter)
            PreviewPill("Fit your face in the guide · tap to focus", Alignment.BottomCenter)
        } else if (state.error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        } else {
            Text(
                "Camera unavailable",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BoxScope.PreviewPill(label: String, alignment: Alignment) {
    Text(
        label,
        modifier = Modifier.align(alignment).padding(16.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.52f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CameraGuidance() {
    Text("A moment for you", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "A clear photo is the best starting point.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
    )
    listOf(
        "01" to "Find soft, even light",
        "02" to "Look straight ahead, with a relaxed expression",
        "03" to "Keep your face clear of glasses and hair",
    ).forEach { (number, message) ->
        Row(
            Modifier.padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                number,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(11.dp),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CameraControls(
    state: CameraUiState,
    busy: Boolean,
    importError: String?,
    onCapture: () -> Unit,
    onFlip: () -> Unit,
    onZoom: (Float) -> Unit,
    onRetry: () -> Unit,
    openPicker: () -> Unit,
) {
    val shutterColor = MaterialTheme.colorScheme.onPrimary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.zoomChoices.forEach { zoom ->
                FilterChip(
                    selected = state.zoom == zoom,
                    onClick = { onZoom(zoom) },
                    label = { Text(formatZoom(zoom)) },
                    enabled = state.ready && !busy,
                    modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "${formatZoom(zoom)} zoom" },
                )
            }
            OutlinedButton(
                onClick = onFlip,
                enabled = state.canFlip && !busy,
                modifier = Modifier.semantics {
                    contentDescription = if (state.front) "Switch to rear camera" else "Switch to front camera"
                },
            ) {
                Text("Flip camera")
            }
        }
        val error = importError ?: state.error
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!state.ready) TextButton(onClick = onRetry, enabled = !busy) { Text("Try camera again") }
        }
        Button(
            onClick = onCapture,
            enabled = state.ready && !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Preparing photo…")
            } else {
                Canvas(Modifier.size(22.dp)) {
                    drawCircle(shutterColor, size.minDimension * 0.42f, style = Stroke(2.dp.toPx()))
                    drawCircle(shutterColor, size.minDimension * 0.28f)
                }
                Spacer(Modifier.width(10.dp))
                Text("Capture photo", fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(onClick = openPicker, enabled = !busy) { Text("Choose an existing photo") }
        Text(
            "Your photo stays on this device",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun CameraPermission(
    permanentlyDenied: Boolean,
    importing: Boolean,
    error: String?,
    onAllow: () -> Unit,
    onClose: () -> Unit,
    openPicker: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        TextButton(onClick = onClose) { Text("Close") }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val accent = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(104.dp).border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(30.dp)).padding(24.dp)) {
                    drawRoundRect(accent, topLeft = Offset(0f, size.height * 0.16f), size = Size(size.width, size.height * 0.70f), style = Stroke(3.dp.toPx()))
                    drawCircle(accent, size.width * 0.2f, Offset(size.width / 2, size.height * 0.5f), style = Stroke(3.dp.toPx()))
                }
                Spacer(Modifier.height(24.dp))
                Text("Let's get you in the frame", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    if (permanentlyDenied) "Enable camera access in Settings to take a photo. You can also choose an existing photo."
                    else "Allow camera access to capture your photo. It stays in your private ScanFace storage on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )
                Button(onClick = onAllow, enabled = !importing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (permanentlyDenied) "Open Settings" else "Allow camera")
                }
                TextButton(onClick = openPicker, enabled = !importing) {
                    Text(if (importing) "Preparing photo…" else "Choose an existing photo")
                }
                if (importing) CircularProgressIndicator(Modifier.size(24.dp))
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
