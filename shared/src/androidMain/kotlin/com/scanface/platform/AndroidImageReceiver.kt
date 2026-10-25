package com.scanface.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.scanface.core.CapturedPhoto
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidImageRequest private constructor(val id: String, val uris: List<Uri>) {
    fun save(): Bundle = Bundle().apply {
        putString("id", id)
        putStringArrayList("uris", ArrayList(uris.map(Uri::toString)))
    }

    internal fun json(): JSONObject = JSONObject().put("id", id).put("uris", JSONArray(uris.map(Uri::toString)))

    companion object {
        fun restore(saved: Bundle): AndroidImageRequest? = runCatching {
            val id = requireNotNull(saved.getString("id"))
            require(UUID.fromString(id).toString() == id)
            val uris = requireNotNull(saved.getStringArrayList("uris")).map(Uri::parse)
            validate(uris)
            AndroidImageRequest(id, uris)
        }.getOrNull()

        internal fun from(intent: Intent): AndroidImageRequest? {
            if (intent.action !in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
            require(intent.type == null || intent.type?.startsWith("image/") == true)
            val uris = when (intent.action) {
                Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                Intent.ACTION_SEND -> listOfNotNull(intent.imageStream()) + intent.clipUris()
                else -> intent.imageStreams() + intent.clipUris()
            }.distinct()
            validate(uris)
            return AndroidImageRequest(UUID.randomUUID().toString(), uris)
        }

        internal fun from(json: JSONObject): AndroidImageRequest {
            val values = json.getJSONArray("uris")
            return requireNotNull(restore(Bundle().apply {
                putString("id", json.getString("id"))
                putStringArrayList("uris", ArrayList(List(values.length()) { values.getString(it) }))
            }))
        }

        private fun validate(uris: List<Uri>) {
            require(uris.isNotEmpty() && uris.size <= 32)
            require(uris.all { it.scheme in listOf("content", "file") && it.toString().length <= 16_384 })
        }

        @Suppress("DEPRECATION")
        private fun Intent.imageStream(): Uri? = if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun Intent.imageStreams(): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

        private fun Intent.clipUris(): List<Uri> = clipData?.let { clip ->
            require(clip.itemCount <= 32)
            List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
        }.orEmpty()
    }
}

class AndroidImageReceiver(context: Context, private val services: PlatformServices) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val active = mutableSetOf<String>()
    private val awaitingStorage = linkedMapOf<String, CapturedPhoto>()
    private val requestTasks = mutableMapOf<String, Int>()

    fun request(intent: Intent, taskId: Int, restored: AndroidImageRequest? = null): AndroidImageRequest? {
        val external = intent.action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
        val delivery = try { deliveries().firstOrNull { it.getInt("task") == taskId } }
        catch (_: Exception) {
            services.incomingImages.reportFailure("Image opening could not be restored. Open the original again from your file or photo app.")
            return null
        }
        val request = try {
            if (restored != null) restored
            else if (!external || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
                if (delivery == null || delivery.getBoolean("completed")) return null
                AndroidImageRequest.from(delivery.getJSONObject("request"))
            } else AndroidImageRequest.from(intent)
        } catch (_: Exception) {
            services.incomingImages.reportFailure("These images could not be opened. Share up to 32 images from a file or photo app.")
            return null
        } ?: return null
        if (delivery?.getJSONObject("request")?.getString("id") == request.id && delivery.getBoolean("completed")) return null
        return try {
            saveDelivery(taskId, request, false)
            requestTasks[request.id] = taskId
            request
        } catch (_: Exception) {
            services.incomingImages.reportFailure("This image request could not be saved. Open it again to retry.")
            null
        }
    }

    fun receive(request: AndroidImageRequest) {
        if (!active.add(request.id)) return
        scope.launch {
            try {
                mutex.withLock {
                    var awaitingWrite = false
                    request.uris.forEachIndexed { index, uri ->
                        val id = "${request.id}-$index"
                        if (services.incomingImages.hasReceived(id)) return@forEachIndexed
                        try {
                            val photo = awaitingStorage[id] ?: withContext(Dispatchers.IO) {
                                importPhoto(context, uri, id, "Imported image")
                            }
                            if (services.incomingImages.receive(id, photo)) awaitingStorage.remove(id)
                            else { awaitingStorage[id] = photo; awaitingWrite = true }
                        } catch (_: ImageImportTooLarge) {
                            services.incomingImages.reportFailure("This image exceeds the 50 MB import limit.")
                        } catch (_: Exception) {
                            services.incomingImages.reportFailure("This image could not be opened. Open the original again from your file or photo app.")
                        }
                    }
                    if (!awaitingWrite) complete(request)
                }
            } finally {
                active.remove(request.id)
            }
        }
    }

    fun retryStorage() {
        awaitingStorage.toList().forEach { (id, photo) ->
            if (services.incomingImages.receive(id, photo)) awaitingStorage.remove(id)
        }
    }

    private fun complete(request: AndroidImageRequest) {
        val task = requestTasks.remove(request.id) ?: return
        runCatching {
            if (deliveries().firstOrNull { it.getInt("task") == task }
                    ?.getJSONObject("request")?.getString("id") == request.id) {
                saveDelivery(task, request, true)
            }
        }
    }

    private fun deliveries(): List<JSONObject> {
        val json = JSONArray(services.storage.get(DELIVERIES_KEY) ?: "[]")
        return List(json.length()) { json.getJSONObject(it) }.also { values ->
            values.forEach {
                require(it.getInt("task") >= 0)
                it.getBoolean("completed")
                AndroidImageRequest.from(it.getJSONObject("request"))
            }
        }
    }

    private fun saveDelivery(taskId: Int, request: AndroidImageRequest, completed: Boolean) {
        val next = deliveries().filterNot { it.getInt("task") == taskId }.takeLast(15) +
            JSONObject().put("task", taskId).put("request", request.json()).put("completed", completed)
        services.storage.put(DELIVERIES_KEY, JSONArray(next).toString())
    }

    private companion object {
        const val DELIVERIES_KEY = "scanface.image-deliveries.v1"
    }
}
