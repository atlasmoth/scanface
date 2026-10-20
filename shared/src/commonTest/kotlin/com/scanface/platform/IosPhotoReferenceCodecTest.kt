package com.scanface.platform

import com.scanface.core.AppStore
import com.scanface.core.CapturedPhoto
import com.scanface.core.KeyValueStore
import com.scanface.core.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class IosPhotoReferenceCodecTest {
    private val oldRoot = "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/ScanFace/Captures"
    private val newRoot = "/var/mobile/Containers/Data/Application/NEW/Library/Application Support/ScanFace/Captures"
    private val savedName = "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE.jpg"
    private val pendingName = "12345678-ABCD-4EF0-8ABC-123456789ABC.jpg"
    private val orphanName = "87654321-DCBA-4FE0-8CBA-CBA987654321.jpg"

    @Test
    fun roundTripRelocatesSavedPendingAndCleanupReferences() {
        val original = snapshot("$oldRoot/$savedName", "$oldRoot/$pendingName", listOf("$oldRoot/$orphanName"))
        val encoded = IosPhotoReferenceCodec(oldRoot).encode(original)
        assertFalse(encoded.contains(oldRoot))
        val disk = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("captures/$savedName", disk["records"]!!.jsonArray.single().jsonObject["photoPath"]!!.jsonPrimitive.content)
        assertEquals("captures/$pendingName", disk["pending"]!!.jsonObject["photoPath"]!!.jsonPrimitive.content)
        assertEquals("captures/$orphanName", disk["cleanupPaths"]!!.jsonArray.single().jsonPrimitive.content)

        val relocatedCodec = IosPhotoReferenceCodec("$newRoot/")
        val decoded = Json.parseToJsonElement(relocatedCodec.decode(encoded)).jsonObject
        assertEquals("$newRoot/$savedName", decoded["records"]!!.jsonArray.single().jsonObject["photoPath"]!!.jsonPrimitive.content)
        assertEquals("$newRoot/$pendingName", decoded["pending"]!!.jsonObject["photoPath"]!!.jsonPrimitive.content)
        assertEquals("$newRoot/$orphanName", decoded["cleanupPaths"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(encoded), Json.parseToJsonElement(relocatedCodec.encode(decoded.toString())))
    }

    @Test
    fun metadataNullPhotosAndUnknownFieldsRemainUntouched() {
        val source = buildJsonObject {
            put("version", 1)
            put("theme", "DARK")
            put("enteredAge", 38)
            put("records", buildJsonArray {
                add(JsonObject(record("metadata-only", null) + mapOf(
                    "age" to JsonPrimitive(36.5),
                    "modelId" to JsonPrimitive("$oldRoot/$savedName"),
                    "futureMetadata" to buildJsonObject { put("photoPath", "not-a-photo-reference") },
                )))
            })
            put("pending", JsonNull)
            put("cleanupPaths", JsonArray(emptyList()))
            put("futureRootField", buildJsonObject { put("photoPath", "leave this alone") })
        }
        val codec = IosPhotoReferenceCodec(newRoot)
        assertEquals(source, Json.parseToJsonElement(codec.encode(source.toString())))
        assertEquals(source, Json.parseToJsonElement(codec.decode(source.toString())))
    }

    @Test
    fun malformedAndUnsupportedDocumentsPassThroughByteForByte() {
        val codec = IosPhotoReferenceCodec(newRoot)
        listOf(
            "", "{ broken JSON", "null", "[]", "42", "\"text\"",
            "{ \"version\": 2, \"pending\": { \"photoPath\": \"unrecognized future reference\" } }",
            "{ \"version\": null, \"records\": [] }",
            "{ \"version\": \"future\", \"cleanupPaths\": [\"anything\"] }",
        ).forEach { source ->
            assertEquals(source, codec.encode(source))
            assertEquals(source, codec.decode(source))
        }
    }

    @Test
    fun rejectsTraversalUnrelatedPathsAndInvalidFilenames() = runTest {
        val codec = IosPhotoReferenceCodec(newRoot)
        val invalid = listOf(
            savedName,
            "captures/../$savedName",
            "captures/subfolder/$savedName",
            "$oldRoot/../Captures/$savedName",
            "$oldRoot/./$savedName",
            "/tmp/$savedName",
            "/Library/Application Support/Other/Captures/$savedName",
            "file://$oldRoot/$savedName",
            "captures/%2e%2e/$savedName",
            "captures/not-a-uuid.jpg",
            "captures/${savedName.removeSuffix(".jpg")}.png",
            "captures/",
        )
        invalid.forEach { reference ->
            assertFails { codec.resolve(reference) }
            assertFails { codec.encode(snapshot(null, reference)) }
            assertFails { codec.decode(snapshot(null, null, listOf(reference))) }
        }
        val original = snapshot(null, null, listOf("$oldRoot/../Captures/$savedName"))
        val bytes = mutableMapOf(AppStore.STORAGE_KEY to original)
        val removed = mutableListOf<String>()
        val app = createStore(CodecStore(bytes, newRoot), removed::add)
        assertNotNull(app.state.value.error)
        assertTrue(removed.isEmpty())
        assertEquals(original, bytes[AppStore.STORAGE_KEY])
    }

    @Test
    fun startupProtectsOwnedAliasesAndDeletesAnUnownedPhotoOnlyOnce() = runTest {
        val bytes = mutableMapOf(AppStore.STORAGE_KEY to snapshot(
            "$oldRoot/${savedName.lowercase()}",
            "captures/${pendingName.lowercase()}",
            listOf(
                "captures/$savedName", "$newRoot/${savedName.lowercase()}",
                "$oldRoot/$pendingName", "captures/$pendingName",
                "$oldRoot/${orphanName.lowercase()}", "$newRoot/$orphanName", "captures/$orphanName",
            ),
        ))
        val remainingFiles = mutableSetOf("$newRoot/$savedName", "$newRoot/$pendingName", "$newRoot/$orphanName")
        val removed = mutableListOf<String>()
        val app = createStore(CodecStore(bytes, newRoot)) { path ->
            removed.add(path)
            remainingFiles.remove(path)
        }
        assertEquals(listOf("$newRoot/$orphanName"), removed)
        assertEquals(setOf("$newRoot/$savedName", "$newRoot/$pendingName"), remainingFiles)
        assertEquals("$newRoot/$savedName", app.state.value.records.single().photoPath)
        assertEquals("$newRoot/$pendingName", app.state.value.pending?.photoPath)
        assertEquals(0, app.state.value.cleanupPendingCount)
        assertFalse(bytes[AppStore.STORAGE_KEY].orEmpty().contains("/var/mobile/"))
        assertTrue(app.retryCleanup())
        assertEquals(1, removed.size)
    }

    @Test
    fun failedDeletionWritePreservesRecordAndLaterCleanupSurvivesRelocation() = runTest {
        val bytes = mutableMapOf<String, String>()
        val disk = CodecStore(bytes, oldRoot)
        var fileLocked = false
        val removed = mutableListOf<String>()
        val app = createStore(disk) { path ->
            check(!fileLocked)
            removed.add(path)
        }
        app.capture(CapturedPhoto("$oldRoot/$savedName", 1200, 1600, "Front wide", false, 1_000))
        val saved = assertNotNull(app.savePending())
        val originalBytes = bytes[AppStore.STORAGE_KEY]
        disk.failWrites = true
        assertFalse(app.deleteRecord(saved.id))
        assertEquals(originalBytes, bytes[AppStore.STORAGE_KEY])
        assertEquals(saved, app.state.value.records.single())
        assertTrue(removed.isEmpty())

        disk.failWrites = false
        fileLocked = true
        assertTrue(app.deleteRecord(saved.id))
        assertEquals(1, app.state.value.cleanupPendingCount)
        assertTrue(removed.isEmpty())
        val relocated = createStore(CodecStore(bytes, newRoot), removed::add)
        assertEquals(listOf("$newRoot/$savedName"), removed)
        assertTrue(relocated.state.value.records.isEmpty())
        assertEquals(0, relocated.state.value.cleanupPendingCount)
    }

    @Test
    fun failedCleanupQueueWriteKeepsJournalAndProtectsOwnedAliasesOnRetry() = runTest {
        val original = snapshot("captures/$savedName", null, listOf(
            "$oldRoot/${savedName.lowercase()}", "captures/$orphanName",
        ))
        val bytes = mutableMapOf(AppStore.STORAGE_KEY to original)
        val disk = CodecStore(bytes, newRoot).apply { failWrites = true }
        val attempted = mutableListOf<String>()
        val files = mutableSetOf("$newRoot/$savedName", "$newRoot/$orphanName")
        val delete: (String) -> Unit = { path ->
            attempted.add(path)
            files.remove(path)
        }
        val first = createStore(disk, delete)
        assertNotNull(first.state.value.error)
        assertEquals(original, bytes[AppStore.STORAGE_KEY])
        assertEquals(listOf("$newRoot/$orphanName"), attempted)
        assertEquals(setOf("$newRoot/$savedName"), files)

        val restored = createStore(CodecStore(bytes, newRoot), delete)
        assertEquals(listOf("$newRoot/$orphanName", "$newRoot/$orphanName"), attempted)
        assertEquals("$newRoot/$savedName", restored.state.value.records.single().photoPath)
        assertEquals(0, restored.state.value.cleanupPendingCount)
        assertNull(restored.state.value.error)
    }

    @Test
    fun malformedSnapshotRecoveryRetainsOriginalBytesWithoutTransformingOtherKeys() = runTest {
        val corrupt = "{ broken snapshot containing $oldRoot/$savedName"
        val bytes = mutableMapOf(AppStore.STORAGE_KEY to corrupt)
        val disk = CodecStore(bytes, newRoot)
        val removed = mutableListOf<String>()
        val app = createStore(disk, removed::add)
        assertNotNull(app.state.value.error)
        assertTrue(app.setTheme(ThemeMode.DARK))
        assertEquals(corrupt, disk.get(AppStore.RECOVERY_KEY))
        assertEquals(corrupt, bytes[AppStore.RECOVERY_KEY])
        val unrelatedValue = snapshot("$oldRoot/$savedName", null)
        disk.put("other.setting", unrelatedValue)
        assertEquals(unrelatedValue, bytes["other.setting"])
        assertEquals(unrelatedValue, disk.get("other.setting"))
        assertTrue(removed.isEmpty())
    }

    private fun snapshot(saved: String?, pending: String?, cleanup: List<String> = emptyList()): String =
        buildJsonObject {
            put("version", 1)
            put("records", buildJsonArray { if (saved != null) add(record("saved", saved)) })
            put("pending", pending?.let { record("pending", it) } ?: JsonNull)
            put("cleanupPaths", JsonArray(cleanup.map(::JsonPrimitive)))
        }.toString()

    private fun record(id: String, path: String?) = buildJsonObject {
        put("id", id)
        put("createdAtMillis", 1_000)
        put("photoPath", path?.let(::JsonPrimitive) ?: JsonNull)
        put("width", 1200)
        put("height", 1600)
    }

    private fun TestScope.createStore(storage: KeyValueStore, delete: (String) -> Unit) = AppStore(
        storage = storage,
        deletePhoto = delete,
        scope = this,
    )

    /** The same boundary used by iOS: only the app snapshot has photo references. */
    private class CodecStore(
        private val bytes: MutableMap<String, String>,
        captureRoot: String,
    ) : KeyValueStore {
        private val codec = IosPhotoReferenceCodec(captureRoot)
        var failWrites = false

        override fun get(key: String): String? = bytes[key]?.let {
            if (key == AppStore.STORAGE_KEY) codec.decode(it) else it
        }

        override fun put(key: String, value: String) {
            check(!failWrites) { "Storage unavailable" }
            bytes[key] = if (key == AppStore.STORAGE_KEY) codec.encode(value) else value
        }

        override fun remove(key: String) {
            bytes.remove(key)
        }
    }
}
