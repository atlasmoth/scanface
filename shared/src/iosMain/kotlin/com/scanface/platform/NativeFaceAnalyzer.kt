package com.scanface.platform

import com.scanface.core.AnalysisResult
import com.scanface.core.CapturedPhoto
import com.scanface.core.FaceAnalyzer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Native work must complete on the main queue, at most once. */
interface NativeFaceAnalyzer {
    fun startAnalysis(photo: CapturedPhoto, onResult: (AnalysisResult) -> Unit): NativeAnalysisTask
}

interface NativeAnalysisTask {
    /** Safe from any thread; cancels queued work and suppresses late callbacks. */
    fun cancel()
}

/** Keeps Kotlin cancellation connected to Vision and the native inference worker. */
class IosFaceAnalyzer(private val native: NativeFaceAnalyzer) : FaceAnalyzer {
    override suspend fun analyze(photo: CapturedPhoto): AnalysisResult = suspendCancellableCoroutine { continuation ->
        val task = native.startAnalysis(photo) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation { task.cancel() }
    }
}
