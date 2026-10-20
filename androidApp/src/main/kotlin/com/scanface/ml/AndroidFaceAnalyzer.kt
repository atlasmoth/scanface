package com.scanface.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import com.scanface.core.AnalysisResult
import com.scanface.core.AnalysisStatus
import com.scanface.core.CapturedPhoto
import com.scanface.core.FaceAnalyzer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** On-device FAHR-FaceAge weights with an explicitly distinct native-detector pipeline. */
class AndroidFaceAnalyzer internal constructor(
    context: Context,
    private val locator: FaceLocator,
    private val model: AgeModel,
) : FaceAnalyzer, AutoCloseable {
    constructor(context: Context) : this(context, BundledFaceDetector(), LiteRtAgeModel(context.applicationContext))

    private val applicationContext = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { work ->
        Thread(work, "scanface-inference").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun analyze(photo: CapturedPhoto): AnalysisResult {
        check(!closed.get()) { "Analyzer is closed" }
        return withContext(dispatcher) {
            mutex.withLock {
                check(!closed.get()) { "Analyzer is closed" }
                var bitmap: Bitmap? = null
                try {
                    currentCoroutineContext().ensureActive()
                    bitmap = decodePrivatePhoto(photo)
                    val faces = locator.findFaces(bitmap)
                    currentCoroutineContext().ensureActive()
                    val alignment = FacePreprocessing.selectAndAlign(faces, bitmap.width, bitmap.height)
                    val pixels = FacePreprocessing.pixels(bitmap, alignment)
                    currentCoroutineContext().ensureActive()
                    val age = model.estimate(pixels)
                    currentCoroutineContext().ensureActive()
                    check(age.isFinite() && age in 0.0..130.0) { "Invalid age" }
                    AnalysisResult(
                        status = AnalysisStatus.COMPLETE,
                        age = age,
                        modelId = LiteRtAgeModel.MODEL_ID,
                        message = "Research estimate from FAHR-FaceAge. Native face detection replaces the study's RetinaFace preprocessing; clinical equivalence has not been established.",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unusable: UnusableFaceException) {
                    failure(unusable.message ?: "Try another photo.")
                } catch (_: MissingAgeModelException) {
                    AnalysisResult(
                        status = AnalysisStatus.AWAITING_MODEL,
                        modelId = LiteRtAgeModel.MODEL_ID,
                        message = "This build is missing its bundled FAHR-FaceAge model. Install a complete build to analyze this photo.",
                    )
                } catch (_: Exception) {
                    failure("This photo could not be analyzed on this device. Try another clear, well-lit photo.")
                } catch (_: LinkageError) {
                    failure("The on-device model runtime is unavailable in this build.")
                } finally {
                    bitmap?.recycle()
                }
            }
        }
    }

    private fun failure(message: String) = AnalysisResult(
        status = AnalysisStatus.FAILED,
        modelId = LiteRtAgeModel.MODEL_ID,
        message = message,
    )

    private fun decodePrivatePhoto(photo: CapturedPhoto): Bitmap {
        val file = File(photo.path).canonicalFile
        require(file.parentFile == File(applicationContext.filesDir, "captures").canonicalFile && file.isFile) {
            "Photo is outside private capture storage"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth == photo.width && bounds.outHeight == photo.height &&
            bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Invalid photo dimensions" }
        return requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        })) { "Unreadable photo" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Wait for an outstanding detector task/inference before releasing native
        // handles. Never recycle a bitmap or close a model still used by that task.
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                mutex.withLock {
                    try { locator.close() } finally { model.close() }
                }
            } finally {
                dispatcher.close()
            }
        }
    }
}
