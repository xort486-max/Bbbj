package com.jarvis.core

import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.SourceLink
import com.jarvis.core.network.AssistEvent
import com.jarvis.core.network.AuthTokenProvider
import com.jarvis.core.network.BackendAssistService
import com.jarvis.core.network.BackendClient
import com.jarvis.core.network.BackendConfiguration
import com.jarvis.core.network.BackendException
import com.jarvis.core.network.HistoryTurn
import com.jarvis.core.network.HttpResponse
import com.jarvis.core.network.OkHttpSseTransport
import com.jarvis.core.routing.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssistServiceTest {
    private val stream = """
        event: route
        data: {"route": "search", "engine": "google", "model": "gemini-4-hydrogen"}

        event: stage
        data: {"stage": "searching"}

        event: delta
        data: {"text": "스페인이 우승했습니다."}

        event: sources
        data: {"provider": "google", "items": [{"title": "uefa.com", "url": "https://uefa.com/a"}, {"title": "", "url": "https://b.com"}], "queries": ["euro 2024"], "search_suggestions_html": "<div>chips</div>"}

        event: done
        data: {"engine": "google", "model": "gemini-4-hydrogen", "cached": false, "input_tokens": 20, "output_tokens": 60, "search_queries": 1}

    """.trimIndent() + "\n"

    @Test
    fun streamsAndParsesAllEventsOverRealHttp() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream").body(stream).build(),
            )
            server.start()
            val service = BackendAssistService(
                BackendConfiguration.validated(server.url("/").toString()),
                OkHttpSseTransport(),
                AuthTokenProvider { "tok" },
            )

            val events = service.assist(Route.SEARCH, "유로 우승팀", listOf(HistoryTurn("user", "안녕"))).toList()

            assertEquals(AssistEvent.RouteInfo(AiEngine.GOOGLE, "gemini-4-hydrogen"), events[0])
            assertEquals(AssistEvent.StageChange("searching", null), events[1])
            assertEquals(AssistEvent.Delta("스페인이 우승했습니다."), events[2])
            val sources = assertIs<AssistEvent.SourcesInfo>(events[3])
            assertEquals(listOf(SourceLink("uefa.com", "https://uefa.com/a"), SourceLink("https://b.com", "https://b.com")), sources.items)
            assertEquals("<div>chips</div>", sources.searchSuggestionsHtml)
            val done = assertIs<AssistEvent.Done>(events[4])
            assertEquals(Triple(20, 60, 1), Triple(done.inputTokens, done.outputTokens, done.searchQueries))

            val recorded = server.takeRequest()
            assertEquals("/v1/assist", recorded.url.encodedPath)
            assertEquals("Bearer tok", recorded.headers["Authorization"])
            val body = recorded.body!!.utf8()
            assertTrue(body.contains("\"route\":\"search\"") && body.contains("\"history\":[{\"role\":\"user\""), body)
        }
    }

    @Test
    fun httpErrorsBeforeStreamAreTyped() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).body("""{"detail":"NVIDIA 엔진을 사용할 수 없습니다: 설정 필요: NVIDIA_API_KEY"}""").build())
            server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"bad"}""").build())
            server.start()
            val service = BackendAssistService(
                BackendConfiguration.validated(server.url("/").toString()),
                OkHttpSseTransport(),
                AuthTokenProvider { "tok" },
            )
            val http = assertFailsWith<BackendException.Http> { service.assist(Route.CHAT, "x", emptyList()).toList() }
            assertEquals(503, http.status)
            assertTrue(http.detail!!.contains("NVIDIA_API_KEY"))
            assertFailsWith<BackendException.Unauthorized> { service.assist(Route.CHAT, "x", emptyList()).toList() }
        }
    }

    @Test
    fun cancellingCollectionClosesTheStream() {
        MockWebServer().use { server ->
            // 첫 이벤트 뒤에 아주 느리게 이어지는 스트림 — 수집을 멈추면 연결이 끊겨야 한다
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body("event: delta\ndata: {\"text\": \"첫 조각\"}\n\n" + "event: delta\ndata: {\"text\": \"x\"}\n\n".repeat(2000))
                    .throttleBody(64, 1, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
            server.start()
            val service = BackendAssistService(
                BackendConfiguration.validated(server.url("/").toString()),
                OkHttpSseTransport(),
                AuthTokenProvider { "tok" },
            )
            val started = System.nanoTime()
            val first = runBlocking { service.assist(Route.CHAT, "x", emptyList()).first() }
            assertEquals(AssistEvent.Delta("첫 조각"), first)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "first() 후 스트림을 끝까지 기다리지 않아야 한다")
        }
    }

    @Test
    fun missingTokenFailsImmediatelyAndDeviceRouteIsRejected() {
        val service = BackendAssistService(BackendConfiguration("https://x.example"), { error("no network") }, AuthTokenProvider { null })
        assertFailsWith<BackendException.MissingToken> { service.assist(Route.CHAT, "x", emptyList()) }
        val withToken = BackendAssistService(BackendConfiguration("https://x.example"), { error("no network") }, AuthTokenProvider { "t" })
        assertFailsWith<IllegalArgumentException> { withToken.assist(Route.DEVICE, "알람", emptyList()) }
    }

    @Test
    fun unknownOrBrokenEventsAreSkipped() {
        val service = BackendAssistService(BackendConfiguration("https://x.example"), { error("unused") }, AuthTokenProvider { "t" })
        assertEquals(null, service.parse(com.jarvis.core.network.SseEvent("ping", "{}")))
        assertEquals(null, service.parse(com.jarvis.core.network.SseEvent("delta", "not json")))
        val failure = assertIs<AssistEvent.Failure>(
            service.parse(com.jarvis.core.network.SseEvent("error", """{"message":"한도 초과","code":"rate_limited","retryable":true}""")),
        )
        assertTrue(failure.retryable)
    }

    @Test
    fun usageEndpointParses() = runTest {
        val body = """{"day":"2026-11-21","timezone":"Asia/Seoul","engines":[
            {"engine":"nvidia","requests":3,"errors":0,"cached":0,"input_tokens":10,"output_tokens":20,"search_queries":0,"daily_limit":0},
            {"engine":"claude","requests":1,"errors":0,"cached":1,"input_tokens":5,"output_tokens":5,"search_queries":2,"daily_limit":50}]}"""
        val client = BackendClient(BackendConfiguration("https://x.example"), { HttpResponse(200, body) }, AuthTokenProvider { "t" })
        val usage = client.usage()
        assertEquals(4, usage.totalRequests)
        assertEquals(40, usage.totalTokens)
        assertEquals(50, usage.of(AiEngine.CLAUDE)!!.dailyLimit)
    }
}
