package com.scanface.ml

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal interface FaceLocator : AutoCloseable {
    suspend fun findFaces(bitmap: Bitmap): List<DetectedFace>
}

/** Uses the bundled model; no model download or Google Play installation is needed. */
internal class BundledFaceDetector : FaceLocator {
    private var detector: FaceDetector? = null

    override suspend fun findFaces(bitmap: Bitmap): List<DetectedFace> {
        val active = detector ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.05f)
                .build(),
        ).also { detector = it }
        // ML Kit does not expose cancellation of this task. Retain the bitmap
        // until it finishes, then the caller checks coroutine cancellation before
        // preprocessing/inference. Recycling on cancellation would race native use.
        return suspendCoroutine { continuation ->
            active.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(Executor { it.run() }) { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result.map { face ->
                        DetectedFace(
                            bounds = Rect(face.boundingBox),
                            eyes = listOfNotNull(
                                face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                                face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                            ).map { PointF(it.x, it.y) },
                            yawDegrees = face.headEulerAngleY,
                            pitchDegrees = face.headEulerAngleX,
                        )
                    })
                } else {
                    continuation.resumeWithException(task.exception ?: IllegalStateException("Face detection failed"))
                }
            }
        }
    }

    override fun close() {
        detector?.close()
        detector = null
    }
}
