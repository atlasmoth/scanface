package com.scanface.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class ModelManifestServiceTest {
    private val endpoint = "https://models.example.test/release.json"
    private val digest = "a".repeat(64)
    private val validManifest = """
        {
          "schemaVersion": 1,
          "id": "example-research-model",
          "version": "1.0",
          "license": "Example research license",
          "modelCardUrl": "https://models.example.test/model-card",
          "artifacts": [
            {"runtime":"litert","url":"https://models.example.test/model.tflite","sha256":"$digest","byteCount":1024},
            {"runtime":"coreml","url":"https://models.example.test/model.mlpackage.zip","sha256":"$digest","byteCount":2048}
          ]
        }
    """.trimIndent()

    @Test
    fun constructionMakesNoRequestsAndFetchIsMetadataOnly() = runTest {
        var requests = 0
        val client = mockClient { request ->
            requests++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(endpoint, request.url.toString())
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            assertEquals(0L, request.body.contentLength)
            respond(validManifest, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val service = ModelManifestService(client)
            assertEquals(0, requests)
            val manifest = service.fetch(endpoint)
            assertEquals(1, requests)
            assertEquals("example-research-model", manifest.id)
            assertEquals(listOf(ModelRuntime.LITERT, ModelRuntime.CORE_ML), manifest.artifacts.map { it.runtime })
            assertTrue(manifest.artifacts.all { it.sha256 == digest })
        } finally {
            client.close()
        }
    }

    @Test
    fun insecureManifestUrlIsRejectedBeforeAnyRequest() = runTest {
        var requests = 0
        val client = mockClient {
            requests++
            respond(validManifest)
        }
        try {
            val service = ModelManifestService(client)
            listOf("http://models.example.test/model", "https://user:password@models.example.test/model").forEach { url ->
                assertFailsWith<ModelManifestException> { service.fetch(url) }
            }
            assertEquals(0, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun httpErrorsAreReportedWithoutParsingBody() = runTest {
        val client = client("<html>Unavailable</html>", HttpStatusCode.ServiceUnavailable)
        try {
            val error = assertFailsWith<ModelManifestException> { ModelManifestService(client).fetch(endpoint) }
            assertTrue(error.message.orEmpty().contains("503"))
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedJsonAndUnsupportedVersionAreRejected() = runTest {
        listOf("{invalid", validManifest.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")).forEach { json ->
            val client = client(json)
            try {
                assertFailsWith<ModelManifestException> { ModelManifestService(client).fetch(endpoint) }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun invalidArtifactIntegrityAndUrlsAreRejected() = runTest {
        listOf(
            validManifest.replace(digest, "too-short"),
            validManifest.replace("\"byteCount\":1024", "\"byteCount\":0"),
            validManifest.replace("https://models.example.test/model.tflite", "http://models.example.test/model.tflite"),
            validManifest.replace("\"runtime\":\"coreml\"", "\"runtime\":\"litert\""),
            validManifest.replace("Example research license", ""),
        ).forEach { json ->
            val client = client(json)
            try {
                assertFailsWith<ModelManifestException> { ModelManifestService(client).fetch(endpoint) }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun oversizedManifestIsBoundedEvenWithoutContentLength() = runTest {
        val client = client(" ".repeat(ModelManifestService.MAX_MANIFEST_BYTES + 1))
        try {
            val error = assertFailsWith<ModelManifestException> { ModelManifestService(client).fetch(endpoint) }
            assertTrue(error.message.orEmpty().contains("too large"))
        } finally {
            client.close()
        }
    }

    @Test
    fun htmlResponseIsNotTreatedAsAManifest() = runTest {
        val client = mockClient {
            respond(validManifest, headers = headersOf(HttpHeaders.ContentType, "text/html"))
        }
        try {
            assertFailsWith<ModelManifestException> { ModelManifestService(client).fetch(endpoint) }
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellationRemainsCancellation() = runTest {
        val client = mockClient { throw CancellationException("request cancelled") }
        try {
            assertFailsWith<CancellationException> { ModelManifestService(client).fetch(endpoint) }
        } finally {
            client.close()
        }
    }

    @Test
    fun stalledManifestRequestTimesOut() = runTest {
        val client = mockClient {
            delay(20_000L)
            respond(validManifest, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            assertFailsWith<TimeoutCancellationException> { ModelManifestService(client).fetch(endpoint) }
        } finally {
            client.close()
        }
    }

    private fun TestScope.client(content: String, status: HttpStatusCode = HttpStatusCode.OK) = mockClient {
        respond(content, status, headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
    }

    private fun TestScope.mockClient(handler: MockRequestHandler) = HttpClient(
        MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }),
    )
}
