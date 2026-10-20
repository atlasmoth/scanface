package com.scanface.platform

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.scanface.core.CapturedPhoto
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CameraFlash(val label: String) {
    OFF("Flash off"), AUTO("Flash auto"), ON("Flash on"), SCREEN("Screen flash"),
}

internal data class CameraUiState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val zoomChanging: Boolean = false,
    val front: Boolean = true,
    val canFlip: Boolean = false,
    val flash: CameraFlash = CameraFlash.OFF,
    val flashes: List<CameraFlash> = listOf(CameraFlash.OFF),
    val zoom: Float = 1f,
    val zoomChoices: List<Float> = listOf(1f),
    val cameraLabel: String = "Front camera",
    val error: String? = null,
    val screenFlashVisible: Boolean = false,
)

/** Owns only its own use cases, and normalizes JPEG orientation before handing it to shared code. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal class CameraController(
    private val context: Context,
    private val activity: Activity?,
    private val previewView: PreviewView,
) {
    private val mutableState = MutableStateFlow(CameraUiState())
    val state = mutableState.asStateFlow()
    private val main = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var preview: Preview? = null
    private var owner: LifecycleOwner? = null
    private var generation = 0
    private var desiredFront = true
    private var oldBrightness: Float? = null
    private var closed = false
    private var captureInFlight = false
    private var zoomGeneration = 0
    private var zoomTimeout: Runnable? = null
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            capture?.targetRotation = rotation
            preview?.targetRotation = rotation
        }
    }

    private val screenFlash = object : ImageCapture.ScreenFlash {
        override fun apply(expirationTimeMillis: Long, screenFlashListener: ImageCapture.ScreenFlashListener) {
            if (closed) {
                screenFlashListener.onCompleted()
                return
            }
            activity?.window?.let { window ->
                if (oldBrightness == null) oldBrightness = window.attributes.screenBrightness
                window.attributes = window.attributes.apply { screenBrightness = 1f }
            }
            mutableState.value = mutableState.value.copy(screenFlashVisible = true)
            // Give Compose a frame to draw the white flash before exposure begins.
            previewView.postOnAnimation {
                previewView.postOnAnimation { screenFlashListener.onCompleted() }
            }
        }

        override fun clear() {
            oldBrightness?.let { previous ->
                activity?.window?.let { window ->
                    window.attributes = window.attributes.apply { screenBrightness = previous }
                }
            }
            oldBrightness = null
            mutableState.value = mutableState.value.copy(screenFlashVisible = false)
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (camera != null) orientationListener.enable()
            Lifecycle.Event.ON_STOP -> {
                orientationListener.disable()
                screenFlash.clear()
            }
            else -> Unit
        }
    }

    init {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.contentDescription = "Camera preview. Tap to focus."
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP && !mutableState.value.busy && !mutableState.value.zoomChanging) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(5, TimeUnit.SECONDS)
                        .build(),
                )
                view.performClick()
            }
            true
        }
    }

    fun start(lifecycleOwner: LifecycleOwner) {
        if (closed || mutableState.value.busy || mutableState.value.zoomChanging) return
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        val token = ++generation
        mutableState.value = mutableState.value.copy(ready = false, error = null)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed || token != generation) return@addListener
            runCatching {
                provider = future.get()
                bindCamera()
            }.onFailure { fail("The camera could not start. Close other camera apps and try again.") }
        }, main)
    }

    fun flip() {
        if (mutableState.value.busy || mutableState.value.zoomChanging || !mutableState.value.canFlip) return
        desiredFront = !mutableState.value.front
        runCatching { bindCamera() }
            .onFailure { fail("This camera is unavailable. Try switching again.") }
    }

    fun cycleFlash() {
        val current = mutableState.value
        if (!current.ready || current.busy || current.zoomChanging) return
        val next = current.flashes[(current.flashes.indexOf(current.flash) + 1) % current.flashes.size]
        capture?.flashMode = next.mode()
        mutableState.value = current.copy(flash = next)
    }

    fun setZoom(value: Float) {
        val current = mutableState.value
        if (!current.ready || current.busy || current.zoomChanging || value == current.zoom || value !in current.zoomChoices) return
        val activeCamera = camera ?: return
        cancelZoomTimeout()
        val token = ++zoomGeneration
        mutableState.value = current.copy(zoomChanging = true, error = null)
        val future = runCatching { activeCamera.cameraControl.setZoomRatio(value) }.getOrElse {
            mutableState.value = current.copy(error = "This zoom is unavailable.")
            return
        }
        zoomTimeout = Runnable {
            if (closed || camera !== activeCamera || token != zoomGeneration) return@Runnable
            // Some camera drivers accept a ratio but never acknowledge its capture
            // request. Rebinding restores an unambiguous 1x baseline for photo metadata.
            runCatching { bindCamera() }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        zoomChoices = mutableState.value.zoomChoices.filter {
                            it != value || it == mutableState.value.zoom
                        },
                        error = "Zoom did not respond. The camera has been reset to ${formatZoom(mutableState.value.zoom)}.",
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(ready = false)
                    fail("The camera could not reset after zooming. Try the camera again.")
                }
        }.also { mainHandler.postDelayed(it, 4_000) }
        future.addListener({
            if (closed || camera !== activeCamera || token != zoomGeneration) return@addListener
            cancelZoomTimeout()
            runCatching { future.get() }
                .onSuccess { mutableState.value = mutableState.value.copy(zoom = value, zoomChanging = false) }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        zoomChanging = false, error = "This zoom is unavailable.",
                    )
                }
        }, main)
    }

    private fun bindCamera() {
        cancelZoomTimeout()
        zoomGeneration++
        val cameraProvider = provider ?: return
        val lifecycleOwner = owner ?: return
        val frontAvailable = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val backAvailable = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        check(frontAvailable || backAvailable) { "No camera" }
        val front = if (desiredFront) frontAvailable else !backAvailable
        desiredFront = front
        val lensFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing)
            .addCameraFilter { candidates ->
                // Prefer the main wide camera (around 24–28 mm equivalent), avoiding ultrawide
                // face distortion and long-telephoto minimum-focus limitations.
                listOfNotNull(candidates.minByOrNull { faceLensScore(it, front) })
            }.build()
        screenFlash.clear()
        listOfNotNull(preview, capture).takeIf { it.isNotEmpty() }
            ?.let { cameraProvider.unbind(*it.toTypedArray()) }
        camera = null
        mutableState.value = CameraUiState(front = front, canFlip = frontAvailable && backAvailable)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            ).build()
        val newPreview = Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation).build()
        val newCapture = ImageCapture.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        newCapture.screenFlash = screenFlash
        newPreview.setSurfaceProvider(previewView.surfaceProvider)
        val bound = cameraProvider.bindToLifecycle(lifecycleOwner, selector, newPreview, newCapture)
        preview = newPreview
        capture = newCapture
        camera = bound
        val zoomState = bound.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 1f
        val initialZoom = 1f.coerceIn(minZoom, maxZoom)
        bound.cameraControl.setZoomRatio(initialZoom)
        val choices = listOf(initialZoom, if (front) 1.5f else 2f)
            .filter { it in minZoom..maxZoom }.distinct()
        val modes = when {
            front && activity != null -> listOf(CameraFlash.OFF, CameraFlash.SCREEN)
            bound.cameraInfo.hasFlashUnit() -> listOf(CameraFlash.OFF, CameraFlash.AUTO, CameraFlash.ON)
            else -> listOf(CameraFlash.OFF)
        }
        mutableState.value = CameraUiState(
            ready = true,
            front = front,
            canFlip = frontAvailable && backAvailable,
            flashes = modes,
            zoom = initialZoom,
            zoomChoices = choices,
            cameraLabel = if (front) "Front camera" else "Main rear camera",
        )
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            orientationListener.enable()
        }
    }

    private fun faceLensScore(info: CameraInfo, front: Boolean): Float = runCatching {
        val characteristics = Camera2CameraInfo.from(info)
        val focal = characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return@runCatching 100f
        val sensor = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return@runCatching 100f
        val equivalent = focal * 36f / sensor.width
        val logical = characteristics.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        abs(equivalent - if (front) 24f else 27f) - if (logical) 2f else 0f
    }.getOrDefault(100f)

    fun takePhoto(onCaptured: (CapturedPhoto) -> Unit) {
        val imageCapture = capture ?: return
        if (!mutableState.value.ready || mutableState.value.busy || mutableState.value.zoomChanging) return
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(busy = true, error = null)
        val token = generation
        val directory = File(context.filesDir, "captures")
        if (!directory.exists() && !directory.mkdirs()) {
            fail("Photo storage is unavailable. Free some space and try again.")
            return
        }
        val timestamp = System.currentTimeMillis()
        val identifier = UUID.randomUUID().toString()
        val raw = File(directory, "$identifier-raw.jpg")
        val normalized = File(directory, "$identifier.jpg")
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        val options = ImageCapture.OutputFileOptions.Builder(raw).setMetadata(metadata).build()
        captureInFlight = true
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val result = runCatching { normalizePhoto(raw, normalized) }
                raw.delete()
                main.execute {
                    captureInFlight = false
                    screenFlash.clear()
                    if (closed || token != generation) {
                        normalized.delete()
                        if (closed) imageExecutor.shutdown()
                        return@execute
                    }
                    mutableState.value = mutableState.value.copy(busy = false)
                    result.fold(
                        onSuccess = { dimensions ->
                            onCaptured(
                                CapturedPhoto(
                                    path = normalized.absolutePath,
                                    width = dimensions.first,
                                    height = dimensions.second,
                                    lens = "${snapshot.cameraLabel} · ${formatZoom(snapshot.zoom)}",
                                    mirrored = false,
                                    capturedAtMillis = timestamp,
                                ),
                            )
                        },
                        onFailure = {
                            normalized.delete()
                            fail("The photo could not be saved. Free some space and try again.")
                        },
                    )
                }
            }

            override fun onError(exception: ImageCaptureException) {
                raw.delete()
                main.execute {
                    captureInFlight = false
                    screenFlash.clear()
                    if (!closed && token == generation) {
                        fail("Capture failed. Keep the camera open and try again.")
                    }
                    if (closed) imageExecutor.shutdown()
                }
            }
        }
        runCatching { imageCapture.takePicture(options, imageExecutor, callback) }
            .onFailure {
                captureInFlight = false
                raw.delete()
                normalized.delete()
                screenFlash.clear()
                fail("Capture could not start. Try again.")
            }
    }

    fun close() {
        closed = true
        generation++
        zoomGeneration++
        cancelZoomTimeout()
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null
        orientationListener.disable()
        screenFlash.clear()
        listOfNotNull(preview, capture).takeIf { it.isNotEmpty() }
            ?.let { provider?.unbind(*it.toTypedArray()) }
        previewView.setOnTouchListener(null)
        camera = null
        capture = null
        preview = null
        // CameraX still delivers a cancelled capture callback after unbinding. Keep its
        // executor alive until that callback can remove any temporary image.
        if (!captureInFlight) imageExecutor.shutdown()
    }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(busy = false, zoomChanging = false, error = message)
    }

    private fun cancelZoomTimeout() {
        zoomTimeout?.let(mainHandler::removeCallbacks)
        zoomTimeout = null
    }
}

internal fun formatZoom(value: Float): String =
    if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"

private fun CameraFlash.mode(): Int = when (this) {
    CameraFlash.OFF -> ImageCapture.FLASH_MODE_OFF
    CameraFlash.AUTO -> ImageCapture.FLASH_MODE_AUTO
    CameraFlash.ON -> ImageCapture.FLASH_MODE_ON
    CameraFlash.SCREEN -> ImageCapture.FLASH_MODE_SCREEN
}

internal fun normalizePhoto(source: File, destination: File): Pair<Int, Int> {
    val exif = ExifInterface(source)
    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image" }
    val options = BitmapFactory.Options().apply {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        inSampleSize = sample
    }
    val bitmap = requireNotNull(BitmapFactory.decodeFile(source.absolutePath, options)) { "Invalid image" }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    var upright: Bitmap? = null
    try {
        upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        destination.outputStream().use { stream ->
            check(upright.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG encoding failed" }
        }
        // Re-encoding removes EXIF, including any location/vendor metadata. Pixels are now upright.
        return upright.width to upright.height
    } finally {
        if (upright != null && upright !== bitmap) upright.recycle()
        bitmap.recycle()
    }
}
