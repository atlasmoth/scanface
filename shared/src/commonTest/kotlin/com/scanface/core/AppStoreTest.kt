package com.scanface.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppStoreTest {
    private val now = 1_792_526_400_000L

    @Test
    fun firstLaunchIsEmptyAndUsesSystemTheme() = runTest {
        val store = store()
        assertTrue(store.state.value.records.isEmpty())
        assertNull(store.state.value.pending)
        assertNull(store.state.value.enteredAge)
        assertEquals(ThemeMode.SYSTEM, store.state.value.theme)
    }

    @Test
    fun realCaptureNeverInventsAnAgeAndSurvivesRestart() = runTest {
        val disk = MemoryStore()
        val first = store(disk)
        first.setEnteredAge(38)
        first.capture(photo())
        advanceUntilIdle()
        val pending = assertNotNull(first.state.value.pending)
        assertNull(pending.age)
        assertNull(pending.modelId)
        assertFalse(pending.isDemo)
        assertEquals(AnalysisStatus.AWAITING_MODEL, pending.status)
        val restored = store(disk)
        assertEquals(pending, restored.state.value.pending)
        assertFalse(restored.state.value.isAnalyzing)
        assertEquals(38, restored.state.value.enteredAge)
    }

    @Test
    fun saveIsIdempotentAndDeleteReleasesPrivateImage() = runTest {
        val disk = MemoryStore()
        val removed = mutableListOf<String>()
        val first = store(disk, removed)
        first.capture(photo())
        advanceUntilIdle()
        val saved = assertNotNull(first.savePending())
        assertNull(first.savePending())
        assertEquals(1, first.state.value.records.size)
        assertNull(first.state.value.pending)
        val restored = store(disk, removed)
        assertEquals(saved, restored.state.value.latest)
        assertTrue(restored.deleteRecord(saved.id))
        assertEquals(listOf("/private/capture.jpg"), removed)
        assertTrue(store(disk).state.value.records.isEmpty())
    }

    @Test
    fun failedSaveKeepsPendingResultAndImage() = runTest {
        val disk = MemoryStore()
        val removed = mutableListOf<String>()
        val first = store(disk, removed)
        first.capture(photo())
        advanceUntilIdle()
        disk.failWrites = true
        assertNull(first.savePending())
        assertNotNull(first.state.value.pending)
        assertTrue(first.state.value.records.isEmpty())
        assertTrue(removed.isEmpty())
        assertNotNull(first.state.value.error)
        disk.failWrites = false
        assertNotNull(first.savePending())
    }

    @Test
    fun failedDeleteDoesNotDestroyImage() = runTest {
        val disk = MemoryStore()
        val removed = mutableListOf<String>()
        val first = store(disk, removed)
        first.capture(photo())
        advanceUntilIdle()
        val saved = assertNotNull(first.savePending())
        disk.failWrites = true
        assertFalse(first.deleteRecord(saved.id))
        assertEquals(1, first.state.value.records.size)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun discardingPendingDeletesOnlyUnownedImage() = runTest {
        val removed = mutableListOf<String>()
        val first = store(removed = removed)
        first.capture(photo())
        advanceUntilIdle()
        assertTrue(first.discardPending())
        assertNull(first.state.value.pending)
        assertEquals(listOf("/private/capture.jpg"), removed)
    }

    @Test
    fun corruptMetadataDoesNotDeleteImagesAndIsPreservedForRecovery() = runTest {
        val disk = MemoryStore()
        disk.put(AppStore.STORAGE_KEY, "{corrupt metadata")
        val removed = mutableListOf<String>()
        val first = store(disk, removed)
        assertNotNull(first.state.value.error)
        assertTrue(first.state.value.records.isEmpty())
        assertTrue(removed.isEmpty())
        assertEquals("{corrupt metadata", disk.get(AppStore.STORAGE_KEY))
        assertTrue(first.setTheme(ThemeMode.DARK))
        assertEquals("{corrupt metadata", disk.get(AppStore.RECOVERY_KEY))
        assertEquals(ThemeMode.DARK, store(disk).state.value.theme)
    }

    @Test
    fun preferenceValidationAndThemePersist() = runTest {
        val disk = MemoryStore()
        val first = store(disk)
        assertTrue(first.setEnteredAge(38))
        assertFalse(first.setEnteredAge(0))
        assertFalse(first.setEnteredAge(121))
        assertEquals(38, first.state.value.enteredAge)
        assertTrue(first.setTheme(ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, store(disk).state.value.theme)
        assertTrue(first.setEnteredAge(null))
        assertNull(store(disk).state.value.enteredAge)
    }

    @Test
    fun sampleDataIsExplicitOrderedAndLabelledInExports() = runTest {
        val first = store()
        assertTrue(first.state.value.records.isEmpty())
        first.seedDemo()
        first.seedDemo()
        val samples = first.state.value.records
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.isDemo && it.photoPath == null })
        assertEquals(listOf(36.0, 37.0, 36.0), samples.map { it.age })
        assertTrue(samples.zipWithNext().all { (a, b) -> a.createdAtMillis > b.createdAtMillis })
        assertTrue(first.exportCsv().contains("FICTIONAL_SAMPLE"))
        assertTrue(first.removeDemo())
        assertTrue(first.state.value.records.isEmpty())
    }

    @Test
    fun exportsNeverLeakPathsAndClearlyMarkPendingModel() = runTest {
        val first = store()
        first.capture(photo())
        advanceUntilIdle()
        val saved = assertNotNull(first.savePending())
        val csv = first.exportCsv(saved.id)
        assertFalse(csv.contains("/private/"))
        assertTrue(csv.contains("AWAITING_MODEL"))
        assertTrue(csv.contains("USER_CAPTURE"))
        assertFalse(csv.contains("FICTIONAL_SAMPLE"))
        assertEquals(2, csv.trim().lines().size)
    }

    @Test
    fun invalidModelOutputNeverBecomesAnEstimate() = runTest {
        val analyzer = object : FaceAnalyzer {
            override suspend fun analyze(photo: CapturedPhoto) =
                AnalysisResult(AnalysisStatus.COMPLETE, Double.NaN, "broken-model")
        }
        val first = store(analyzer = analyzer)
        first.capture(photo())
        advanceUntilIdle()
        val pending = assertNotNull(first.state.value.pending)
        assertEquals(AnalysisStatus.FAILED, pending.status)
        assertNull(pending.age)
    }

    @Test
    fun discardingCancelsAnalysisWithoutResurrectingResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val analyzer = object : FaceAnalyzer {
            override suspend fun analyze(photo: CapturedPhoto): AnalysisResult {
                gate.await()
                return AnalysisResult(AnalysisStatus.COMPLETE, 36.0, "test-model")
            }
        }
        val removed = mutableListOf<String>()
        val first = store(removed = removed, analyzer = analyzer)
        first.capture(photo())
        assertTrue(first.state.value.isAnalyzing)
        first.discardPending()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(first.state.value.pending)
        assertFalse(first.state.value.isAnalyzing)
        assertTrue(first.state.value.records.isEmpty())
        assertEquals(listOf("/private/capture.jpg"), removed)
    }

    @Test
    fun lateOldCaptureCannotReplaceNewCapture() = runTest {
        val removed = mutableListOf<String>()
        val first = store(removed = removed)
        first.capture(photo("/private/old.jpg", now - 10))
        first.capture(photo("/private/new.jpg", now))
        advanceUntilIdle()
        assertEquals("/private/new.jpg", first.state.value.pending?.photoPath)
        assertEquals(listOf("/private/old.jpg"), removed)
    }

    @Test
    fun sharingSameOwnedPathDoesNotDeleteSavedPhotoOnDiscard() = runTest {
        val removed = mutableListOf<String>()
        val first = store(removed = removed)
        first.capture(photo())
        advanceUntilIdle()
        first.savePending()
        first.capture(photo())
        advanceUntilIdle()
        first.discardPending()
        assertTrue(removed.isEmpty())
        assertEquals(1, first.state.value.records.size)
    }

    @Test
    fun failedPreferenceWriteDoesNotAllowSavingWhileAnalysisRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val disk = MemoryStore()
        val first = store(disk, analyzer = delayedAnalyzer(gate))
        first.capture(photo())
        disk.failWrites = true
        assertFalse(first.setTheme(ThemeMode.DARK))
        assertTrue(first.state.value.isAnalyzing)
        assertNull(first.savePending())
        disk.failWrites = false
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(36.0, first.state.value.pending?.age)
        assertNotNull(first.savePending())
    }

    @Test
    fun failedReplacementLeavesOriginalAnalysisActiveAndCleansNewImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val disk = MemoryStore()
        val removed = mutableListOf<String>()
        val first = store(disk, removed, delayedAnalyzer(gate))
        first.capture(photo("/private/original.jpg"))
        disk.failWrites = true
        first.capture(photo("/private/replacement.jpg"))
        assertEquals("/private/original.jpg", first.state.value.pending?.photoPath)
        assertTrue(first.state.value.isAnalyzing)
        assertTrue(removed.isEmpty())
        assertEquals(1, first.state.value.cleanupPendingCount)
        disk.failWrites = false
        assertTrue(first.retryCleanup())
        assertEquals(listOf("/private/replacement.jpg"), removed)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(36.0, first.state.value.pending?.age)
        assertEquals("/private/original.jpg", first.savePending()?.photoPath)
    }

    @Test
    fun invalidReplacementDoesNotCancelExistingAnalysis() = runTest {
        val gate = CompletableDeferred<Unit>()
        val removed = mutableListOf<String>()
        val first = store(removed = removed, analyzer = delayedAnalyzer(gate))
        first.capture(photo())
        first.capture(photo("/private/broken.jpg").copy(width = 0))
        assertTrue(first.state.value.isAnalyzing)
        assertEquals("/private/capture.jpg", first.state.value.pending?.photoPath)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(36.0, first.state.value.pending?.age)
        assertEquals(listOf("/private/broken.jpg"), removed)
    }

    @Test
    fun failedAnalysisResultWriteKeepsDurablePhotoAndSettlesProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val disk = MemoryStore()
        val first = store(disk, analyzer = delayedAnalyzer(gate))
        first.capture(photo())
        disk.failWrites = true
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(first.state.value.isAnalyzing)
        assertNull(first.state.value.pending?.age)
        assertEquals(AnalysisStatus.AWAITING_MODEL, first.state.value.pending?.status)
        assertNotNull(first.state.value.error)
        assertEquals(first.state.value.pending, store(disk).state.value.pending)
    }

    @Test
    fun unreadableStorageCannotOverwriteUnseenHistory() = runTest {
        val disk = MemoryStore()
        val first = store(disk)
        first.seedDemo()
        val savedBytes = disk.get(AppStore.STORAGE_KEY)
        disk.failReads = true
        val inaccessible = store(disk)
        assertNotNull(inaccessible.state.value.error)
        assertFalse(inaccessible.setTheme(ThemeMode.DARK))
        disk.failReads = false
        assertEquals(savedBytes, disk.get(AppStore.STORAGE_KEY))
        assertEquals(3, store(disk).state.value.records.size)
    }

    @Test
    fun cancellingReturnedJobImmediatelySettlesProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val first = store(analyzer = delayedAnalyzer(gate))
        first.capture(photo()).cancel()
        advanceUntilIdle()
        assertFalse(first.state.value.isAnalyzing)
        assertNotNull(first.state.value.pending)
        assertNull(first.state.value.pending?.age)
    }

    @Test
    fun nonCooperativeOldAnalyzerCannotOverwriteNewResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val analyzer = object : FaceAnalyzer {
            override suspend fun analyze(photo: CapturedPhoto): AnalysisResult {
                if (photo.path.endsWith("old.jpg")) withContext(NonCancellable) { gate.await() }
                return AnalysisResult(AnalysisStatus.COMPLETE, if (photo.path.endsWith("old.jpg")) 45.0 else 36.0, "test")
            }
        }
        val first = store(analyzer = analyzer)
        first.capture(photo("/private/old.jpg"))
        first.capture(photo("/private/new.jpg"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("/private/new.jpg", first.state.value.pending?.photoPath)
        assertEquals(36.0, first.state.value.pending?.age)
    }

    @Test
    fun metadataWithUnidentifiedCompletedEstimateIsRejected() = runTest {
        val disk = MemoryStore()
        val raw = """{"records":[{"id":"bad","createdAtMillis":1,"age":36.0,"status":"COMPLETE"}]}"""
        disk.put(AppStore.STORAGE_KEY, raw)
        val first = store(disk)
        assertTrue(first.state.value.records.isEmpty())
        assertNotNull(first.state.value.error)
        assertEquals(raw, disk.get(AppStore.STORAGE_KEY))
    }

    @Test
    fun failedFileDeletionIsJournaledBeforeAttemptAndRetriedAfterRestart() = runTest {
        val disk = MemoryStore()
        var failDelete = true
        val removed = mutableListOf<String>()
        val delete: (String) -> Unit = { path ->
            assertTrue(disk.get(AppStore.STORAGE_KEY).orEmpty().contains("\"cleanupPaths\":[\"$path\"]"))
            check(!failDelete) { "Photo is locked" }
            removed.add(path)
        }
        val first = store(disk, delete = delete)
        first.capture(photo())
        val saved = assertNotNull(first.savePending())
        assertTrue(first.deleteRecord(saved.id))
        assertTrue(first.state.value.records.isEmpty())
        assertEquals(1, first.state.value.cleanupPendingCount)
        assertNotNull(first.state.value.error)
        assertTrue(removed.isEmpty())
        failDelete = false
        val restored = store(disk, delete = delete)
        assertEquals(listOf("/private/capture.jpg"), removed)
        assertEquals(0, restored.state.value.cleanupPendingCount)
        assertTrue(restored.state.value.records.isEmpty())
        assertEquals(0, store(disk).state.value.cleanupPendingCount)
    }

    @Test
    fun failedDiscardRemainsRetryableAcrossRestarts() = runTest {
        val disk = MemoryStore()
        var failDelete = true
        val removed = mutableListOf<String>()
        val delete: (String) -> Unit = { path ->
            check(!failDelete)
            removed.add(path)
        }
        val first = store(disk, delete = delete)
        first.capture(photo())
        assertTrue(first.discardPending())
        assertNull(first.state.value.pending)
        assertEquals(1, first.state.value.cleanupPendingCount)
        val restored = store(disk, delete = delete)
        assertEquals(1, restored.state.value.cleanupPendingCount)
        assertFalse(restored.retryCleanup())
        failDelete = false
        assertTrue(restored.retryCleanup())
        assertEquals(0, restored.state.value.cleanupPendingCount)
        assertNull(restored.state.value.error)
        assertEquals(listOf("/private/capture.jpg"), removed)
    }

    @Test
    fun recapturingQueuedPathTransfersOwnershipWithoutDeletingIt() = runTest {
        val disk = MemoryStore()
        var failDelete = true
        val removed = mutableListOf<String>()
        val first = store(disk, delete = { path ->
            check(!failDelete)
            removed.add(path)
        })
        first.capture(photo())
        first.discardPending()
        assertEquals(1, first.state.value.cleanupPendingCount)
        first.capture(photo())
        failDelete = false
        assertTrue(first.retryCleanup())
        assertEquals(0, first.state.value.cleanupPendingCount)
        assertTrue(removed.isEmpty())
        assertEquals("/private/capture.jpg", first.savePending()?.photoPath)
        store(disk, removed)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun loadedCleanupQueueNeverDeletesAnOwnedPhoto() = runTest {
        val disk = MemoryStore()
        val first = store(disk)
        first.capture(photo())
        first.savePending()
        disk.put(AppStore.STORAGE_KEY, assertNotNull(disk.get(AppStore.STORAGE_KEY)).replace(
            "\"cleanupPaths\":[]", "\"cleanupPaths\":[\"/private/capture.jpg\"]",
        ))
        val removed = mutableListOf<String>()
        val restored = store(disk, removed)
        assertEquals(1, restored.state.value.records.size)
        assertEquals(0, restored.state.value.cleanupPendingCount)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun failedQueueClearingWriteSafelyRetriesAnAlreadyDeletedFile() = runTest {
        val disk = MemoryStore()
        val existingFiles = mutableSetOf("/private/capture.jpg")
        var attempts = 0
        val first = store(disk, delete = { path ->
            attempts++
            existingFiles.remove(path)
            disk.failWrites = true
        })
        first.capture(photo())
        val record = assertNotNull(first.savePending())
        assertTrue(first.deleteRecord(record.id))
        assertTrue(existingFiles.isEmpty())
        assertEquals(1, first.state.value.cleanupPendingCount)
        disk.failWrites = false
        val restored = store(disk, delete = { path ->
            attempts++
            existingFiles.remove(path)
        })
        assertEquals(2, attempts)
        assertEquals(0, restored.state.value.cleanupPendingCount)
        assertTrue(restored.state.value.records.isEmpty())
    }

    @Test
    fun replacementCleanupFailureDoesNotDeleteNewCapture() = runTest {
        val disk = MemoryStore()
        var failDelete = true
        val removed = mutableListOf<String>()
        val first = store(disk, delete = { path ->
            check(!failDelete)
            removed.add(path)
        })
        first.capture(photo("/private/old.jpg"))
        first.capture(photo("/private/new.jpg"))
        assertEquals("/private/new.jpg", first.state.value.pending?.photoPath)
        assertEquals(1, first.state.value.cleanupPendingCount)
        failDelete = false
        assertTrue(first.retryCleanup())
        assertEquals(listOf("/private/old.jpg"), removed)
        assertEquals("/private/new.jpg", first.state.value.pending?.photoPath)
    }

    @Test
    fun failedCaptureWaitsForDurableQueueBeforeDeletingItsPhoto() = runTest {
        val disk = MemoryStore()
        var failDelete = true
        val removed = mutableListOf<String>()
        val delete: (String) -> Unit = { path ->
            assertTrue(disk.get(AppStore.STORAGE_KEY).orEmpty().contains("\"cleanupPaths\":[\"$path\"]"))
            check(!failDelete)
            removed.add(path)
        }
        val first = store(disk, delete = delete)
        disk.failWrites = true
        first.capture(photo().copy(width = 0))
        assertNull(first.state.value.pending)
        assertEquals(1, first.state.value.cleanupPendingCount)
        assertTrue(removed.isEmpty())
        disk.failWrites = false
        assertFalse(first.retryCleanup())
        assertTrue(removed.isEmpty())
        failDelete = false
        val restored = store(disk, delete = delete)
        assertEquals(0, restored.state.value.cleanupPendingCount)
        assertEquals(listOf("/private/capture.jpg"), removed)
    }

    @Test
    fun clearHistoryKeepsOnlyFailedCleanupEntriesAndProtectsPendingImage() = runTest {
        val disk = MemoryStore()
        val removed = mutableListOf<String>()
        val first = store(disk, delete = { path ->
            check(path != "/private/locked.jpg")
            removed.add(path)
        })
        listOf("/private/ready.jpg", "/private/locked.jpg", "/private/owned.jpg").forEach { path ->
            first.capture(photo(path))
            first.savePending()
        }
        first.capture(photo("/private/owned.jpg"))
        assertTrue(first.clearHistory())
        assertTrue(first.state.value.records.isEmpty())
        assertEquals("/private/owned.jpg", first.state.value.pending?.photoPath)
        assertEquals(listOf("/private/ready.jpg"), removed)
        assertEquals(1, first.state.value.cleanupPendingCount)
        val restored = store(disk, removed)
        assertEquals(listOf("/private/ready.jpg", "/private/locked.jpg"), removed)
        assertEquals("/private/owned.jpg", restored.state.value.pending?.photoPath)
        assertEquals(0, restored.state.value.cleanupPendingCount)
    }

    private fun delayedAnalyzer(gate: CompletableDeferred<Unit>) = object : FaceAnalyzer {
        override suspend fun analyze(photo: CapturedPhoto): AnalysisResult {
            gate.await()
            return AnalysisResult(AnalysisStatus.COMPLETE, 36.0, "test-model")
        }
    }

    private fun TestScope.store(
        disk: MemoryStore = MemoryStore(),
        removed: MutableList<String> = mutableListOf(),
        analyzer: FaceAnalyzer = PendingModelAnalyzer(),
        delete: ((String) -> Unit)? = null,
    ) = AppStore(
        storage = disk,
        analyzer = analyzer,
        nowMillis = { now },
        deletePhoto = { path -> if (delete != null) delete(path) else removed.add(path) },
        scope = this,
    )

    private fun photo(path: String = "/private/capture.jpg", timestamp: Long = now) =
        CapturedPhoto(path, 1200, 1600, "Front wide", false, timestamp)

    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        var failWrites = false
        var failReads = false
        override fun get(key: String): String? {
            check(!failReads) { "Storage unavailable" }
            return values[key]
        }
        override fun put(key: String, value: String) {
            check(!failWrites) { "Disk full" }
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
