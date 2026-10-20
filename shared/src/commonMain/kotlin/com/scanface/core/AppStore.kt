package com.scanface.core

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * App-wide state with one durable JSON snapshot. Call mutations on the UI dispatcher;
 * the supplied scope must also use that dispatcher. Disk writes happen before state
 * changes are published, so a failed save never presents an unsaved result as saved.
 */
class AppStore(
    private val storage: KeyValueStore,
    private val analyzer: FaceAnalyzer = PendingModelAnalyzer(),
    private val nowMillis: () -> Long,
    private val deletePhoto: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var unreadableSnapshot: String? = null
    private var initialReadFailed = false
    private var cleanupPaths = emptyList<String>()
    private var durableCleanupPaths = emptySet<String>()
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private var analysisJob: Job? = null
    private var captureGeneration = 0L

    init {
        if (cleanupPaths.isNotEmpty()) retryCleanup()
    }

    fun capture(photo: CapturedPhoto): Job {
        val previous = mutableState.value.pending
        if (photo.path.isBlank() || photo.width <= 0 || photo.height <= 0 || photo.capturedAtMillis < 0) {
            fail("The camera did not return a readable photo. Please try again.")
            queueUnownedPhoto(photo.path)
            return scope.launch {}
        }
        val pending = ScanRecord(
            id = "${photo.capturedAtMillis.toString(16)}-${Random.nextLong().toULong().toString(16)}",
            createdAtMillis = photo.capturedAtMillis,
            photoPath = photo.path,
            enteredAge = mutableState.value.enteredAge,
            lens = photo.lens,
            width = photo.width,
            height = photo.height,
            mirrored = photo.mirrored,
            analysisMessage = "Preparing your photo.",
        )
        if (!commit(
            mutableState.value.copy(pending = pending, isAnalyzing = true, error = null),
            listOfNotNull(previous?.photoPath),
        )) {
            queueUnownedPhoto(photo.path)
            return scope.launch {}
        }
        // Replace ownership durably before cancelling the previous capture. A failed
        // replacement must leave its predecessor able to finish and be saved.
        val generation = ++captureGeneration
        analysisJob?.cancel()
        retryCleanup()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ensureActive()
                val result = analyzer.analyze(photo)
                if (generation != captureGeneration || mutableState.value.pending?.id != pending.id) {
                    return@launch
                }
                val validEstimate = result.age?.takeIf { it.isFinite() && it in 0.0..130.0 }
                val valid = result.status != AnalysisStatus.COMPLETE ||
                    (validEstimate != null && !result.modelId.isNullOrBlank())
                val completed = pending.copy(
                    age = if (valid && result.status == AnalysisStatus.COMPLETE) validEstimate else null,
                    status = if (valid) result.status else AnalysisStatus.FAILED,
                    modelId = result.modelId,
                    analysisMessage = if (valid) result.message else
                        "The model returned an invalid result. No estimate has been saved.",
                )
                if (!commit(mutableState.value.copy(pending = completed, isAnalyzing = false))) {
                    // The durable pending photo remains intact; this transient flag
                    // must settle even if storage rejected the analysis result.
                    mutableState.value = mutableState.value.copy(isAnalyzing = false)
                }
            } catch (cancelled: CancellationException) {
                if (generation == captureGeneration) {
                    mutableState.value = mutableState.value.copy(isAnalyzing = false)
                }
                throw cancelled
            } catch (_: Exception) {
                if (generation == captureGeneration && mutableState.value.pending?.id == pending.id) {
                    if (!commit(mutableState.value.copy(
                        pending = pending.copy(
                            status = AnalysisStatus.FAILED,
                            analysisMessage = "This photo could not be analyzed. You can keep it and try again later.",
                        ),
                        isAnalyzing = false,
                    ))) {
                        mutableState.value = mutableState.value.copy(isAnalyzing = false)
                    }
                }
            }
        }.also { analysisJob = it }
    }

    /** Returns the saved record, or null if there was no pending result or persistence failed. */
    fun savePending(): ScanRecord? {
        val current = mutableState.value
        val pending = current.pending ?: return null
        if (current.isAnalyzing) return null
        val next = current.copy(
            records = sorted(current.records.filterNot { it.id == pending.id } + pending),
            pending = null,
            error = null,
        )
        return if (commit(next)) pending else null
    }

    fun discardPending(): Boolean {
        val previous = mutableState.value.pending
        if (!commit(
            mutableState.value.copy(pending = null, isAnalyzing = false, error = null),
            listOfNotNull(previous?.photoPath),
        )) return false
        ++captureGeneration
        analysisJob?.cancel()
        retryCleanup()
        return true
    }

    fun deleteRecord(id: String): Boolean {
        val current = mutableState.value
        val record = current.records.find { it.id == id } ?: return true
        if (!commit(
            current.copy(records = current.records.filterNot { it.id == id }, error = null),
            listOfNotNull(record.photoPath),
        )) {
            return false
        }
        retryCleanup()
        return true
    }

    fun clearHistory(): Boolean {
        val previous = mutableState.value.records
        if (!commit(
            mutableState.value.copy(records = emptyList(), error = null),
            previous.mapNotNull { it.photoPath },
        )) return false
        retryCleanup()
        return true
    }

    fun setTheme(mode: ThemeMode): Boolean =
        commit(mutableState.value.copy(theme = mode, error = null))

    fun setEnteredAge(age: Int?): Boolean {
        if (age != null && age !in 1..120) {
            fail("Enter an age from 1 to 120, or leave it blank.")
            return false
        }
        return commit(mutableState.value.copy(enteredAge = age, error = null))
    }

    /** Demo data is opt-in and remains visibly distinguishable in UI and exports. */
    fun seedDemo(): Boolean {
        if (mutableState.value.records.any { it.isDemo }) return true
        val now = nowMillis()
        val demo = listOf(36.0, 37.0, 36.0).mapIndexed { index, age ->
            ScanRecord(
                id = "demo-$now-$index",
                createdAtMillis = now - index * 7L * 24 * 60 * 60 * 1_000,
                age = age,
                enteredAge = 38,
                isDemo = true,
                modelId = "Sample data",
                lens = "Sample",
                status = AnalysisStatus.COMPLETE,
                analysisMessage = "Fictional sample data to preview ScanFace. This is not an analysis of your face.",
            )
        }
        return commit(mutableState.value.copy(records = sorted(mutableState.value.records + demo), error = null))
    }

    fun removeDemo(): Boolean {
        val current = mutableState.value
        if (!commit(
            current.copy(records = current.records.filterNot { it.isDemo }, error = null),
            current.records.filter { it.isDemo }.mapNotNull { it.photoPath },
        )) return false
        retryCleanup()
        return true
    }

    /**
     * Deletes only unowned images whose cleanup request has already reached durable
     * storage. Completed entries are removed afterward, so a crash can safely retry.
     * Platform deletion must be idempotent when a file no longer exists.
     */
    fun retryCleanup(): Boolean {
        if (cleanupPaths.isEmpty()) return true
        val current = mutableState.value
        val unowned = cleanupPaths.filterNot { isOwned(it, current) }
        if (unowned.any { it !in durableCleanupPaths } && !persist(current, unowned)) return false
        val remaining = unowned.filter { path ->
            if (isOwned(path, mutableState.value)) false else try {
                deletePhoto(path)
                false
            } catch (_: Exception) {
                true
            }
        }
        val error = when {
            remaining.isNotEmpty() -> CLEANUP_ERROR
            current.error == CLEANUP_ERROR || current.error == STORAGE_ERROR -> null
            else -> current.error
        }
        if (!persist(mutableState.value.copy(error = error), remaining)) return false
        return remaining.isEmpty()
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    /** Metadata only: this never exports image bytes, local paths, or undisclosed identifiers. */
    fun exportCsv(recordId: String? = null): String {
        val records = mutableState.value.records.filter { recordId == null || it.id == recordId }
        val header = "captured_at_epoch_ms,facial_age_years,entered_age_years,status,data_source,model"
        return buildString {
            appendLine(header)
            records.forEach { record ->
                appendLine(listOf(
                    record.createdAtMillis.toString(),
                    record.age?.toString().orEmpty(),
                    record.enteredAge?.toString().orEmpty(),
                    record.status.name,
                    if (record.isDemo) "FICTIONAL_SAMPLE" else "USER_CAPTURE",
                    record.modelId.orEmpty(),
                ).joinToString(",") { csvCell(it) })
            }
        }
    }

    private fun commit(next: AppState, cleanupCandidates: List<String> = emptyList()): Boolean =
        persist(next, (cleanupPaths + cleanupCandidates).filter { it.isNotBlank() && !isOwned(it, next) }.distinct())

    private fun persist(next: AppState, nextCleanupPaths: List<String>): Boolean {
        if (initialReadFailed) {
            fail("Saved data is temporarily unavailable. Restart the app to try reading it again.")
            return false
        }
        return try {
            unreadableSnapshot?.let { raw ->
                storage.put(RECOVERY_KEY, raw)
                unreadableSnapshot = null
            }
            storage.put(STORAGE_KEY, json.encodeToString(
                Snapshot(
                    records = sorted(next.records),
                    theme = next.theme,
                    enteredAge = next.enteredAge,
                    pending = next.pending,
                    cleanupPaths = nextCleanupPaths,
                ),
            ))
            cleanupPaths = nextCleanupPaths
            durableCleanupPaths = nextCleanupPaths.toSet()
            mutableState.value = next.copy(records = sorted(next.records), cleanupPendingCount = nextCleanupPaths.size)
            true
        } catch (_: Exception) {
            fail(STORAGE_ERROR)
            false
        }
    }

    private fun load(): AppState {
        var raw: String? = null
        return try {
            raw = storage.get(STORAGE_KEY)
            if (raw == null) AppState() else {
                val saved = json.decodeFromString<Snapshot>(raw)
                require(saved.version == 1) { "Unsupported snapshot version" }
                require(saved.enteredAge == null || saved.enteredAge in 1..120)
                require(saved.records.all(::validRecord) && (saved.pending?.let(::validRecord) ?: true))
                require(saved.records.map { it.id }.distinct().size == saved.records.size)
                require(saved.pending == null || saved.records.none { it.id == saved.pending.id })
                require(saved.cleanupPaths.none { it.isBlank() })
                cleanupPaths = saved.cleanupPaths.distinct()
                durableCleanupPaths = cleanupPaths.toSet()
                AppState(
                    records = sorted(saved.records),
                    theme = saved.theme,
                    enteredAge = saved.enteredAge,
                    pending = saved.pending?.copy(
                        analysisMessage = saved.pending.analysisMessage ?: "Your photo is ready for analysis.",
                    ),
                    cleanupPendingCount = cleanupPaths.size,
                )
            }
        } catch (_: Exception) {
            // Preserve original bytes before the next successful write. Never delete images on a failed read.
            unreadableSnapshot = raw
            // If get() itself failed, the unseen snapshot cannot safely be replaced.
            initialReadFailed = raw == null
            AppState(error = "Saved data could not be read. Your existing photos have not been deleted.")
        }
    }

    private fun validRecord(record: ScanRecord): Boolean =
        record.id.isNotBlank() && record.createdAtMillis >= 0 &&
            (record.enteredAge == null || record.enteredAge in 1..120) &&
            (record.age == null || (record.age.isFinite() && record.age in 0.0..130.0)) &&
            (record.status != AnalysisStatus.COMPLETE || (record.age != null && !record.modelId.isNullOrBlank())) &&
            (record.status == AnalysisStatus.COMPLETE || record.age == null)

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    private fun queueUnownedPhoto(path: String) {
        if (path.isBlank() || isOwned(path, mutableState.value)) return
        // If storage itself is unavailable, retain the new file and request in
        // memory until a later write succeeds. Never delete before journaling.
        cleanupPaths = (cleanupPaths + path).distinct()
        mutableState.value = mutableState.value.copy(cleanupPendingCount = cleanupPaths.size)
        retryCleanup()
    }

    private fun isOwned(path: String, state: AppState): Boolean =
        state.pending?.photoPath == path || state.records.any { it.photoPath == path }

    private fun sorted(records: List<ScanRecord>) =
        records.sortedWith(compareByDescending<ScanRecord> { it.createdAtMillis }.thenBy { it.id })

    private fun csvCell(value: String): String {
        // Prefix spreadsheet formula markers, in addition to normal RFC 4180 escaping.
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val records: List<ScanRecord> = emptyList(),
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val enteredAge: Int? = null,
        val pending: ScanRecord? = null,
        val cleanupPaths: List<String> = emptyList(),
    )

    companion object {
        const val STORAGE_KEY = "scanface.snapshot.v1"
        const val RECOVERY_KEY = "scanface.snapshot.recovery"
        private const val STORAGE_ERROR = "Your changes could not be saved on this device. Please try again."
        private const val CLEANUP_ERROR = "Some photos could not be deleted. Retry photo cleanup in Settings."
    }
}
