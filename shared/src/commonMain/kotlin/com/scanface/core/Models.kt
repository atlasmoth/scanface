package com.scanface.core

import kotlinx.serialization.Serializable

/** An upright, privately stored image produced by the platform camera. */
data class CapturedPhoto(
    val path: String,
    val width: Int,
    val height: Int,
    val lens: String,
    val mirrored: Boolean,
    val capturedAtMillis: Long,
)

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AnalysisStatus { AWAITING_MODEL, COMPLETE, FAILED }

@Serializable
data class ScanRecord(
    val id: String,
    val createdAtMillis: Long,
    val photoPath: String? = null,
    val age: Double? = null,
    val enteredAge: Int? = null,
    val isDemo: Boolean = false,
    val modelId: String? = null,
    val lens: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val mirrored: Boolean = false,
    val status: AnalysisStatus = AnalysisStatus.AWAITING_MODEL,
    val analysisMessage: String? = null,
) {
    val ageDifference: Double?
        get() = age?.let { estimate -> enteredAge?.let { estimate - it } }
}

data class AnalysisResult(
    val status: AnalysisStatus,
    val age: Double? = null,
    val modelId: String? = null,
    val message: String? = null,
)

/**
 * The model integration boundary. Implementations receive an upright original photo
 * and must perform their own detector/alignment/normalization contract.
 */
interface FaceAnalyzer {
    suspend fun analyze(photo: CapturedPhoto): AnalysisResult
}

/** Fallback for a platform/build where a real on-device model is unavailable. */
class PendingModelAnalyzer : FaceAnalyzer {
    override suspend fun analyze(photo: CapturedPhoto) = AnalysisResult(
        status = AnalysisStatus.AWAITING_MODEL,
        message = "The on-device age model is unavailable in this build. No facial-age estimate was produced.",
    )
}

data class AppState(
    val records: List<ScanRecord> = emptyList(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val enteredAge: Int? = null,
    val pending: ScanRecord? = null,
    val isAnalyzing: Boolean = false,
    val error: String? = null,
    val cleanupPendingCount: Int = 0,
) {
    val latest: ScanRecord? get() = records.firstOrNull()
}
