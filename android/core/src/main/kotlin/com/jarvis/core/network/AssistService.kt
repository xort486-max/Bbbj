package com.jarvis.core.network

import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.SourceLink
import com.jarvis.core.routing.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 백엔드 `/v1/assist` 스트림 이벤트. */
sealed interface AssistEvent {
    data class RouteInfo(val engine: AiEngine, val model: String) : AssistEvent
    data class Notice(val text: String) : AssistEvent
    data class StageChange(val stage: String, val detail: String?) : AssistEvent
    data class Delta(val text: String) : AssistEvent
    data class SourcesInfo(
        val provider: String,
        val items: List<SourceLink>,
        val queries: List<String>,
        /** Google 검색 제안 위젯 HTML — 근거 결과와 함께 원본 그대로 표시해야 한다 (Gemini 약관). */
        val searchSuggestionsHtml: String?,
    ) : AssistEvent
    data class Done(
        val engine: AiEngine?,
        val model: String,
        val cached: Boolean,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val searchQueries: Int,
    ) : AssistEvent
    data class Failure(val message: String, val code: String, val retryable: Boolean) : AssistEvent
}

data class HistoryTurn(val role: String, val content: String)

/** AI 요청 서비스. 앱에서는 백엔드 구현, 테스트에서는 가짜 구현을 쓴다. */
fun interface AssistService {
    fun assist(route: Route, text: String, history: List<HistoryTurn>): Flow<AssistEvent>
}

/** SSE 이벤트 한 개 (`event:` 이름 + `data:` 본문). */
data class SseEvent(val event: String, val data: String)

class BackendAssistService(
    private val configuration: BackendConfiguration,
    private val transport: SseTransport,
    private val tokenProvider: AuthTokenProvider,
) : AssistService {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Body(val route: String, val text: String, val history: List<Turn>)

    @Serializable
    private data class Turn(val role: String, val content: String)

    override fun assist(route: Route, text: String, history: List<HistoryTurn>): Flow<AssistEvent> {
        val backendRoute = requireNotNull(route.backendId) { "$route 경로는 백엔드에서 처리하지 않습니다" }
        val token = tokenProvider.currentToken()?.takeIf { it.isNotBlank() } ?: throw BackendException.MissingToken()
        val body = json.encodeToString(
            Body.serializer(),
            Body(backendRoute, text, history.map { Turn(it.role, it.content) }),
        )
        val request = HttpRequest(
            url = configuration.url("/v1/assist"),
            method = "POST",
            headers = mapOf(
                "Accept" to "text/event-stream",
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token",
            ),
            body = body,
            timeoutMillis = configuration.timeoutMillis,
        )
        return transport.open(request).mapNotNull { parse(it) }
    }

    internal fun parse(event: SseEvent): AssistEvent? {
        val data = runCatching { json.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = data[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String) = data[key]?.jsonPrimitive?.intOrNull
        return when (event.event) {
            "route" -> AiEngine.fromId(str("engine").orEmpty())?.let { AssistEvent.RouteInfo(it, str("model").orEmpty()) }
            "notice" -> str("text")?.let { AssistEvent.Notice(it) }
            "stage" -> str("stage")?.let { AssistEvent.StageChange(it, str("detail")) }
            "delta" -> str("text")?.let { AssistEvent.Delta(it) }
            "sources" -> AssistEvent.SourcesInfo(
                provider = str("provider").orEmpty(),
                items = data["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SourceLink(obj["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: url, url)
                },
                queries = data["queries"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                searchSuggestionsHtml = str("search_suggestions_html")?.ifBlank { null },
            )
            "done" -> AssistEvent.Done(
                engine = AiEngine.fromId(str("engine").orEmpty()),
                model = str("model").orEmpty(),
                cached = data["cached"]?.jsonPrimitive?.booleanOrNull ?: false,
                inputTokens = int("input_tokens"),
                outputTokens = int("output_tokens"),
                searchQueries = int("search_queries") ?: 0,
            )
            "error" -> AssistEvent.Failure(
                message = str("message") ?: "알 수 없는 오류",
                code = str("code") ?: "unknown",
                retryable = data["retryable"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            else -> null
        }
    }
}
