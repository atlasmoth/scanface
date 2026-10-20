package com.scanface.platform

import kotlinx.serialization.json.*

/** Disk references are stable; callers and the analyzer receive current absolute paths. */
internal class IosPhotoReferenceCodec(captureRoot: String) {
    private val root = captureRoot.trimEnd('/')

    fun resolve(reference: String): String = "$root/${filename(reference)}"

    fun encode(snapshot: String): String = transform(snapshot) { "$PREFIX${filename(it)}" }

    fun decode(snapshot: String): String = transform(snapshot, ::resolve)

    private fun filename(reference: String): String {
        val name = when {
            reference.startsWith(PREFIX) -> reference.removePrefix(PREFIX)
            reference.startsWith('/') &&
                reference.substringBeforeLast('/').endsWith(LEGACY_DIRECTORY) &&
                reference.split('/').none { it == "." || it == ".." } -> reference.substringAfterLast('/')
            else -> error("Invalid private photo reference.")
        }
        require(FILENAME.matches(name)) { "Invalid private photo reference." }
        return name.removeSuffix(".jpg").uppercase() + ".jpg"
    }

    private fun transform(snapshot: String, reference: (String) -> String): String {
        // AppStore owns malformed/unsupported snapshot recovery. Keep its source bytes intact.
        val document = runCatching { Json.parseToJsonElement(snapshot) }.getOrNull() as? JsonObject
            ?: return snapshot
        val version = document["version"]
        if (version != null && (version as? JsonPrimitive)?.intOrNull != 1) return snapshot
        fun path(value: JsonElement): JsonElement =
            if (value is JsonPrimitive && value.isString) JsonPrimitive(reference(value.content)) else value
        fun record(value: JsonElement): JsonElement {
            if (value !is JsonObject || "photoPath" !in value) return value
            return JsonObject(value + ("photoPath" to path(value.getValue("photoPath"))))
        }
        return JsonObject(document.mapValues { (key, value) ->
            when {
                key == "records" && value is JsonArray -> JsonArray(value.map(::record))
                key == "pending" -> record(value)
                key == "cleanupPaths" && value is JsonArray -> JsonArray(value.map(::path))
                else -> value
            }
        }).toString()
    }

    private companion object {
        const val PREFIX = "captures/"
        const val LEGACY_DIRECTORY = "/Library/Application Support/ScanFace/Captures"
        val FILENAME = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg")
    }
}
