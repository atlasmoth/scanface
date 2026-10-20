package com.scanface.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scanface.core.AnalysisStatus
import com.scanface.core.CapturedPhoto
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FahrModelInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun realBundledModelMatchesReferenceTensors() {
        val metadata = context.assets.open("models/conversion.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        assertEquals(LiteRtAgeModel.MODEL_ID, metadata.getString("model_id"))
        assertEquals("int8-weight", metadata.getString("candidate"))
        val reference = metadata.getJSONObject("predictions_years")
        val gradient = FloatArray(112 * 112 * 3)
        for (y in 0 until 112) for (x in 0 until 112) {
            val offset = (y * 112 + x) * 3
            gradient[offset] = ((3 * x + y) % 256).toFloat()
            gradient[offset + 1] = ((x + 2 * y) % 256).toFloat()
            gradient[offset + 2] = ((7 * x + 5 * y) % 256).toFloat()
        }
        LiteRtAgeModel(context).use { model ->
            // Runtime parity is against this exact selected artifact. Drift from
            // original float32 weights is independently gated by the installer.
            assertEquals(reference.getDouble("gradient"), model.estimate(gradient), .02)
            assertEquals(reference.getDouble("gray"), model.estimate(FloatArray(gradient.size) { 127f }), .02)
        }
    }

    @Test
    fun tiltedEyeAlignmentMatchesAuthorPillowPixels() {
        val reference = JSONObject(fixture("alignment.json").decodeToString())
        val width = reference.getInt("width")
        val height = reference.getInt("height")
        val source = fixture(reference.getString("input"))
        val expected = fixture(reference.getString("expected"))
        val bitmap = Bitmap.createBitmap(IntArray(width * height) { index ->
            Color.rgb(source[index * 3].toInt() and 255, source[index * 3 + 1].toInt() and 255,
                source[index * 3 + 2].toInt() and 255)
        }, width, height, Bitmap.Config.ARGB_8888)
        try {
            val box = reference.getJSONArray("bounding_box")
            val eyes = reference.getJSONArray("eyes")
            val face = DetectedFace(
                Rect(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3)),
                List(2) { index -> PointF(eyes.getJSONArray(index).getDouble(0).toFloat(),
                    eyes.getJSONArray(index).getDouble(1).toFloat()) }, 0f, 0f,
            )
            val alignment = FacePreprocessing.selectAndAlign(listOf(face), width, height)
            val crop = reference.getJSONArray("rotated_crop")
            assertEquals(Rect(crop.getInt(0), crop.getInt(1), crop.getInt(2), crop.getInt(3)), alignment.crop)
            assertEquals(reference.getDouble("rotation_radians"), alignment.angleRadians, 1e-10)
            val actual = FacePreprocessing.pixels(bitmap, alignment)
            assertEquals(expected.size, actual.size)
            val maxError = actual.indices.maxOf { abs(actual[it] - (expected[it].toInt() and 255)) }
            assertTrue("Maximum author-Pillow pixel error was $maxError", maxError <= reference.getInt("max_pixel_error"))
            // ML Kit's subject-left label must not alter alignment when eye order changes.
            assertEquals(alignment, FacePreprocessing.selectAndAlign(listOf(face.copy(eyes = face.eyes.reversed())), width, height))
        } finally { bitmap.recycle() }
    }

    @Test
    fun bundledDetectorRejectsBlankImageWithoutRunningAgeModel() = runBlocking {
        val model = RecordingAgeModel()
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val photo = privatePhoto(bitmap)
        AndroidFaceAnalyzer(context, BundledFaceDetector(), model).use { analyzer ->
            try {
                val result = analyzer.analyze(photo)
                assertEquals(AnalysisStatus.FAILED, result.status)
                assertTrue(result.message.orEmpty().startsWith("No face was found"))
                assertEquals(0, model.calls)
                assertEquals(null, result.age)
            } finally { File(photo.path).delete(); bitmap.recycle() }
        }
    }

    @Test
    fun bundledDetectorRejectsMultipleFacesWithoutRunningAgeModel() = runBlocking {
        val bitmap = portraitCanvas(twoFaces = true)
        val photo = privatePhoto(bitmap)
        val model = RecordingAgeModel()
        AndroidFaceAnalyzer(context, BundledFaceDetector(), model).use { analyzer ->
            try {
                val result = analyzer.analyze(photo)
                assertEquals(AnalysisStatus.FAILED, result.status)
                assertTrue(result.message.orEmpty().startsWith("More than one face was found"))
                assertEquals(0, model.calls)
            } finally { File(photo.path).delete(); bitmap.recycle() }
        }
    }

    @Test
    fun realDetectorAndModelAnalyzePortraitEntirelyOnDevice() = runBlocking {
        val bitmap = portraitCanvas(twoFaces = false)
        val photo = privatePhoto(bitmap)
        AndroidFaceAnalyzer(context).use { analyzer ->
            try {
                val result = analyzer.analyze(photo)
                assertEquals(result.message, AnalysisStatus.COMPLETE, result.status)
                assertTrue(requireNotNull(result.age).isFinite() && result.age!! in 0.0..130.0)
                assertEquals(LiteRtAgeModel.MODEL_ID, result.modelId)
                assertTrue(result.message.orEmpty().contains("Native face detection"))
            } finally { File(photo.path).delete(); bitmap.recycle() }
        }
    }

    @Test
    fun cancellationWaitsForDetectorOwnershipAndNeverRunsAgeModel() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var recycledDuringDetection = false
        val locator = object : FaceLocator {
            override suspend fun findFaces(bitmap: Bitmap): List<DetectedFace> {
                entered.complete(Unit)
                // Match ML Kit's non-cancellable completion contract.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { complete.await() }
                recycledDuringDetection = bitmap.isRecycled
                return listOf(DetectedFace(Rect(100, 100, 400, 400), listOf(PointF(180f, 200f), PointF(300f, 200f)), 0f, 0f))
            }
            override fun close() = Unit
        }
        val model = RecordingAgeModel()
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val photo = privatePhoto(bitmap)
        AndroidFaceAnalyzer(context, locator, model).use { analyzer ->
            try {
                val task = async { analyzer.analyze(photo) }
                entered.await()
                task.cancel()
                complete.complete(Unit)
                task.cancelAndJoin()
                assertFalse(recycledDuringDetection)
                assertEquals(0, model.calls)
            } finally { File(photo.path).delete(); bitmap.recycle() }
        }
    }

    @Test
    fun missingModelIsReportedWithoutInventingAnAge() = runBlocking {
        val locator = object : FaceLocator {
            override suspend fun findFaces(bitmap: Bitmap) = listOf(
                DetectedFace(Rect(100, 100, 400, 400), listOf(PointF(180f, 200f), PointF(300f, 200f)), 0f, 0f),
            )
            override fun close() = Unit
        }
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val photo = privatePhoto(bitmap)
        AndroidFaceAnalyzer(context, locator, LiteRtAgeModel(context, "models/absent-test-model.tflite")).use { analyzer ->
            try {
                val result = analyzer.analyze(photo)
                assertEquals(AnalysisStatus.AWAITING_MODEL, result.status)
                assertEquals(null, result.age)
                assertTrue(result.message.orEmpty().contains("missing its bundled"))
            } finally { File(photo.path).delete(); bitmap.recycle() }
        }
    }

    private fun fixture(name: String): ByteArray = try {
        instrumentation.context.assets.open(name).use { it.readBytes() }
    } catch (failure: Exception) {
        throw AssertionError("Missing model test fixture $name. Run scripts/models/prepare_models.py and rebuild androidTest.", failure)
    }

    private fun portraitCanvas(twoFaces: Boolean): Bitmap {
        val bytes = fixture("author-face.png")
        val portrait = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val scaled = Bitmap.createScaledBitmap(portrait, 400, 550, true)
        try {
            return Bitmap.createBitmap(if (twoFaces) 1000 else 600, 750, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.rgb(180, 180, 180))
                Canvas(it).apply {
                    drawBitmap(scaled, 100f, 100f, null)
                    if (twoFaces) drawBitmap(scaled, 550f, 100f, null)
                }
            }
        } finally {
            if (scaled !== portrait) scaled.recycle()
            portrait.recycle()
        }
    }

    private fun privatePhoto(bitmap: Bitmap): CapturedPhoto {
        val directory = File(context.filesDir, "captures").apply { mkdirs() }
        val file = File(directory, "test-${UUID.randomUUID()}.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return CapturedPhoto(file.absolutePath, bitmap.width, bitmap.height, "Instrumentation fixture", false, System.currentTimeMillis())
    }

    private class RecordingAgeModel : AgeModel {
        var calls = 0
        override fun estimate(pixels: FloatArray): Double { calls++; return 35.0 }
        override fun close() = Unit
    }
}
