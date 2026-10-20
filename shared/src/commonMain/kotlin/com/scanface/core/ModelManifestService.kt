package com.scanface.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ModelRuntime {
    @SerialName("litert") LITERT,
    @SerialName("coreml") CORE_ML,
}

/** Release metadata only. The analyzer still owns its preprocessing and inference contract. */
@Serializable
data class ModelManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val license: String,
    val modelCardUrl: String,
    val artifacts: List<ModelArtifact>,
)

@Serializable
data class ModelArtifact(
    val runtime: ModelRuntime,
    val url: String,
    val sha256: String,
    val byteCount: Long,
)

class ModelManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Optional Ktor boundary for a future model release. The app supplies a trusted HTTPS
 * manifest URL explicitly; constructing this service makes no network requests.
 * No photo, user profile, or device identifier is accepted or uploaded here.
 *
 * This validates metadata, not downloaded weights. Integration must verify the
 * artifact's checksum and license before loading it into LiteRT or Core ML.
 * The caller owns the injected client's lifecycle and redirect policy.
 */
class ModelManifestService(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(url: String): ModelManifest {
        requireSecureUrl(url)
        return try {
            withTimeout(15_000L) {
                client.prepareGet(url) {
                    accept(ContentType.Application.Json)
                    expectSuccess = false
                }.execute { response ->
                    requireSecureUrl(response.request.url.toString())
                    if (response.status != HttpStatusCode.OK) {
                        throw ModelManifestException("The model manifest server returned HTTP ${response.status.value}.")
                    }
                    val contentType = response.contentType()
                    if (contentType?.contentType != "application" ||
                        (contentType.contentSubtype != "json" && !contentType.contentSubtype.endsWith("+json"))) {
                        throw ModelManifestException("The model manifest must be JSON.")
                    }
                    if ((response.contentLength() ?: 0) > MAX_MANIFEST_BYTES) {
                        throw ModelManifestException("The model manifest is too large.")
                    }
                    // Stream at most one small metadata document, including when the
                    // server omits Content-Length or sends chunked content.
                    val bytes = ByteArray(MAX_MANIFEST_BYTES + 1)
                    val channel = response.bodyAsChannel()
                    var size = 0
                    while (size < bytes.size) {
                        val count = channel.readAvailable(bytes, size, bytes.size - size)
                        if (count == -1) break
                        size += count
                    }
                    if (size > MAX_MANIFEST_BYTES) {
                        throw ModelManifestException("The model manifest is too large.")
                    }
                    val manifest = json.decodeFromString<ModelManifest>(bytes.decodeToString(0, size))
                    validate(manifest)
                    manifest
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: ModelManifestException) {
            throw invalid
        } catch (failure: Exception) {
            throw ModelManifestException("The model manifest could not be loaded.", failure)
        }
    }

    private fun validate(manifest: ModelManifest) {
        if (manifest.schemaVersion != 1) {
            throw ModelManifestException("This model manifest version is not supported.")
        }
        if (manifest.id.isBlank() || manifest.version.isBlank() || manifest.license.isBlank()) {
            throw ModelManifestException("The model manifest is missing release or license information.")
        }
        requireSecureUrl(manifest.modelCardUrl)
        if (manifest.artifacts.isEmpty() ||
            manifest.artifacts.map { it.runtime }.distinct().size != manifest.artifacts.size) {
            throw ModelManifestException("The model manifest must identify one artifact per supported runtime.")
        }
        manifest.artifacts.forEach { artifact ->
            requireSecureUrl(artifact.url)
            if (artifact.byteCount <= 0 || !artifact.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                throw ModelManifestException("A model artifact is missing valid size or checksum information.")
            }
        }
    }

    private fun requireSecureUrl(value: String) {
        val url = try {
            Url(value)
        } catch (_: Exception) {
            throw ModelManifestException("Model release URLs must be valid HTTPS URLs.")
        }
        if (!value.startsWith("https://", ignoreCase = true) || url.protocol != URLProtocol.HTTPS ||
            url.host.isBlank() || url.user != null || url.password != null || url.fragment.isNotEmpty()) {
            throw ModelManifestException("Model release URLs must use HTTPS without embedded credentials or fragments.")
        }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 65_536
    }
}
