package com.scanface.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class IncomingImage(val id: String, val photo: CapturedPhoto)

data class IncomingImageState(
    val photos: List<IncomingImage> = emptyList(),
    val error: String? = null,
    val canRetry: Boolean = false,
)

class IncomingImageQueue(
    private val storage: KeyValueStore,
    private val deletePhoto: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var readFailed = false
    private var snapshot = load()
    private var unsaved = emptyList<IncomingImage>()
    private val mutableState = MutableStateFlow(IncomingImageState(
        photos = snapshot.photos,
        error = if (readFailed) READ_ERROR else null,
    ))
    val state: StateFlow<IncomingImageState> = mutableState.asStateFlow()

    init {
        if (!readFailed) cleanRemovedPhotos()
    }

    fun hasReceived(id: String): Boolean = id in snapshot.receivedIds || snapshot.photos.any { it.id == id }

    fun receive(id: String, photo: CapturedPhoto): Boolean {
        if (hasReceived(id)) return true
        if (id.isBlank() || !validPhoto(photo)) {
            reportFailure("This image could not be opened. Try another photo.")
            return false
        }
        if (unsaved.none { it.id == id }) unsaved = unsaved + IncomingImage(id, photo)
        return retry()
    }

    fun acknowledge(id: String): Boolean {
        if (snapshot.photos.none { it.id == id }) return true
        return persist(snapshot.copy(
            photos = snapshot.photos.filterNot { it.id == id },
            receivedIds = (snapshot.receivedIds.filterNot { it == id } + id).takeLast(128),
        ))
    }

    fun discard(id: String): Boolean {
        val request = snapshot.photos.firstOrNull { it.id == id } ?: return true
        if (!persist(snapshot.copy(
            photos = snapshot.photos.filterNot { it.id == id },
            cleanupPaths = (snapshot.cleanupPaths + request.photo.path).distinct(),
        ))) return false
        cleanRemovedPhotos()
        return true
    }

    fun retry(): Boolean {
        if (readFailed) { reportFailure(READ_ERROR); return false }
        val additions = unsaved.filterNot { hasReceived(it.id) }
        if (!persist(snapshot.copy(
            photos = snapshot.photos + additions,
            receivedIds = (snapshot.receivedIds + additions.map { it.id }).distinct().takeLast(128),
        ))) return false
        unsaved = emptyList()
        cleanRemovedPhotos()
        return true
    }

    fun reportFailure(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun persist(next: Snapshot): Boolean {
        if (readFailed) { reportFailure(READ_ERROR); return false }
        return try {
            storage.put(STORAGE_KEY, json.encodeToString(next))
            snapshot = next
            mutableState.value = IncomingImageState(photos = next.photos)
            true
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = WRITE_ERROR, canRetry = true)
            false
        }
    }

    private fun cleanRemovedPhotos() {
        if (snapshot.cleanupPaths.isEmpty()) return
        val protected = snapshot.photos.map { it.photo.path }.toSet()
        val remaining = snapshot.cleanupPaths.filterNot { it in protected }.filter { path ->
            try { deletePhoto(path); false } catch (_: Exception) { true }
        }
        if (remaining != snapshot.cleanupPaths && !persist(snapshot.copy(cleanupPaths = remaining))) return
        if (remaining.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(
                error = "Some imported photos could not be removed. Retry to finish cleanup.", canRetry = true,
            )
        }
    }

    private fun load(): Snapshot = try {
        val raw = storage.get(STORAGE_KEY)
        if (raw == null) Snapshot() else json.decodeFromString<Snapshot>(raw).also { saved ->
            require(saved.version == 1)
            require(saved.photos.all { it.id.isNotBlank() && validPhoto(it.photo) })
            require(saved.photos.map { it.id }.distinct().size == saved.photos.size)
            require(saved.receivedIds.none { it.isBlank() } && saved.cleanupPaths.none { it.isBlank() })
        }
    } catch (_: Exception) {
        readFailed = true
        Snapshot()
    }

    private fun validPhoto(photo: CapturedPhoto): Boolean =
        photo.path.isNotBlank() && photo.width > 0 && photo.height > 0 && photo.capturedAtMillis >= 0

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val photos: List<IncomingImage> = emptyList(),
        val receivedIds: List<String> = emptyList(),
        val cleanupPaths: List<String> = emptyList(),
    )

    companion object {
        const val STORAGE_KEY = "scanface.incoming-images.v1"
        private const val READ_ERROR = "Incoming photos could not be read. Restart the app to try again. Your saved scans are safe."
        private const val WRITE_ERROR = "This photo could not be queued on this device. Retry to keep it."
    }
}
