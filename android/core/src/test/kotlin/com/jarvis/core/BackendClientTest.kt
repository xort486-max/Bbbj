package com.jarvis.core

import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import com.jarvis.core.network.AuthTokenProvider
import com.jarvis.core.network.BackendClient
import com.jarvis.core.network.BackendConfiguration
import com.jarvis.core.network.BackendException
import com.jarvis.core.network.EngineStatusMapper
import com.jarvis.core.network.HttpRequest
import com.jarvis.core.network.HttpResponse
import com.jarvis.core.network.HttpTransport
import com.jarvis.core.network.OkHttpTransport
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }
}

class BackendConfigurationTest {
    @Test
    fun acceptsHttpsAndTrimsTrailingSlash() {
        val config = BackendConfiguration.validated("  https://jarvis.example.com/  ")
        assertEquals("https://jarvis.example.com", config.baseUrl)
        assertEquals("https://jarvis.example.com/health", config.url("/health"))
        assertEquals("https://jarvis.example.com/v1/engines", config.url("v1/engines"))
    }

    @Test
    fun rejectsPlainHttpOnPublicHost() {
        assertFailsWith<BackendConfiguration.ValidationError.InsecureScheme> {
            BackendConfiguration.validated("http://jarvis.example.com")
        }
        assertFailsWith<BackendConfiguration.ValidationError.InsecureScheme> {
            BackendConfiguration.validated("http://8.8.8.8:8000")
        }
    }

    @Test
    fun allowsHttpForLocalDevelopment() {
        listOf(
            "http://localhost:8000", "http://127.0.0.1:8000", "http://10.0.2.2:8000", "http://192.168.0.12:8000",
            "http://172.20.1.1", "http://my-pc.local:8000", "http://[::1]:8000",
        ).forEach { BackendConfiguration.validated(it) }
        assertFalse(BackendConfiguration.isLocalDevelopmentHost("172.32.0.1"))
        assertFalse(BackendConfiguration.isLocalDevelopmentHost("192.169.0.1"))
    }

    @Test
    fun rejectsEmptyAndMalformed() {
        assertFailsWith<BackendConfiguration.ValidationError.Empty> { BackendConfiguration.validated("   ") }
        assertFailsWith<BackendConfiguration.ValidationError.InvalidUrl> { BackendConfiguration.validated("jarvis.example.com") }
        assertFailsWith<BackendConfiguration.ValidationError.InvalidUrl> { BackendConfiguration.validated("ftp://jarvis.example.com") }
        assertFailsWith<BackendConfiguration.ValidationError.InvalidUrl> { BackendConfiguration.validated("https://exa mple.com") }
    }
}

class BackendClientTest {
    private val config = BackendConfiguration("https://jarvis.example.com")
    private fun token(value: String?) = AuthTokenProvider { value }

    @Test
    fun healthDoesNotSendToken() = runTest {
        val transport = RecordingTransport { HttpResponse(200, """{"status":"ok","service":"jarvis-backend","version":"0.1.0"}""") }
        val health = BackendClient(config, transport, token("secret")).health()
        assertTrue(health.isOk)
        assertEquals("0.1.0", health.version)
        assertEquals("https://jarvis.example.com/health", transport.requests.single().url)
        assertNull(transport.requests.single().headers["Authorization"])
    }

    @Test
    fun enginesSendsBearerTokenAndMapsStatuses() = runTest {
        val body = """
            {"engines":[
              {"id":"nvidia","configured":true,"provider":"NVIDIA API Catalog","model":"some/model","note":null},
              {"id":"google","configured":false,"provider":"미선택","model":null,"note":"설정 필요"},
              {"id":"unknown-engine","configured":true,"provider":"x"}
            ]}
        """.trimIndent()
        val transport = RecordingTransport { HttpResponse(200, body) }
        val response = BackendClient(config, transport, token("secret")).engines()
        assertEquals("Bearer secret", transport.requests.single().headers["Authorization"])
        assertEquals(3, response.engines.size)

        val statuses = EngineStatusMapper.statuses(response)
        assertEquals(ConnectionStatus.Configured, statuses[AiEngine.NVIDIA])
        assertEquals(ConnectionStatus.NotConfigured, statuses[AiEngine.GOOGLE])
        assertEquals(setOf(AiEngine.NVIDIA, AiEngine.GOOGLE), statuses.keys, "원격 엔진만 상태를 가진다")
        assertNull(statuses[AiEngine.LOCAL])
    }

    @Test
    fun enginesWithoutTokenFailsBeforeNetwork() = runTest {
        val transport = RecordingTransport { HttpResponse(200, "{}") }
        assertFailsWith<BackendException.MissingToken> { BackendClient(config, transport, token(null)).engines() }
        assertFailsWith<BackendException.MissingToken> { BackendClient(config, transport, token("  ")).engines() }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun statusCodeMapping() = runTest {
        suspend fun call(status: Int, body: String): Exception =
            runCatching { BackendClient(config, { HttpResponse(status, body) }, token("t")).engines() }
                .exceptionOrNull() as Exception

        assertIs<BackendException.Unauthorized>(call(401, """{"detail":"invalid"}"""))
        val notConfigured = assertIs<BackendException.ServerNotConfigured>(call(503, """{"detail":"JARVIS_APP_TOKEN not set"}"""))
        assertEquals("JARVIS_APP_TOKEN not set", notConfigured.detail)
        val http = assertIs<BackendException.Http>(call(500, """{"detail":"boom"}"""))
        assertEquals(500 to "boom", http.status to http.detail)
        val noDetail = assertIs<BackendException.Http>(call(502, "not json"))
        assertNull(noDetail.detail)
    }

    @Test
    fun networkAndDecodingErrors() = runTest {
        val offline = BackendClient(config, { throw IOException("오프라인") }, token("t"))
        val network = assertFailsWith<BackendException.Network> { offline.health() }
        assertEquals("오프라인", network.reason)

        val garbage = BackendClient(config, { HttpResponse(200, """{"unexpected":true}""") }, token("t"))
        assertFailsWith<BackendException.Decoding> { garbage.health() }
    }

    @Test
    fun okHttpTransportAgainstRealServer() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok","service":"jarvis-backend","version":"0.1.0"}""").build())
            server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"인증 토큰이 올바르지 않습니다."}""").build())
            server.start()

            val client = BackendClient(
                BackendConfiguration.validated(server.url("/").toString()),
                OkHttpTransport(),
                token("real-token"),
            )
            assertTrue(client.health().isOk)
            assertFailsWith<BackendException.Unauthorized> { client.engines() }

            assertEquals("/health", server.takeRequest().url.encodedPath)
            assertEquals("Bearer real-token", server.takeRequest().headers["Authorization"])
        }
    }
}
