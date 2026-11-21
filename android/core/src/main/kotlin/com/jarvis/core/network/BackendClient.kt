package com.jarvis.core.network

import com.jarvis.core.model.AiEngine
import com.jarvis.core.model.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `GET /health` 응답. */
@Serializable
data class HealthResponse(val status: String, val service: String, val version: String) {
    val isOk: Boolean get() = status == "ok"
}

/** `GET /v1/engines` 응답 — 백엔드에 설정된 AI 엔진 정보 (API Key 값은 포함하지 않음). */
@Serializable
data class EnginesResponse(val engines: List<EngineInfo>)

@Serializable
data class EngineInfo(
    /** "nvidia" | "google" | "claude" */
    val id: String,
    /** API Key 및 필수 설정값이 백엔드에 존재하는지 (실제 호출 성공 여부와는 별개) */
    val configured: Boolean,
    val provider: String,
    val model: String? = null,
    /** 설정 누락 항목 등 안내 문구 */
    val note: String? = null,
) {
    val engine: AiEngine? get() = AiEngine.fromId(id)
}

@Serializable
private data class ErrorBody(val detail: String? = null)

/** `GET /v1/usage` 응답 — 오늘의 엔진별 사용량. */
@Serializable
data class UsageResponse(val day: String, val timezone: String, val engines: List<EngineUsage>) {
    val totalRequests: Int get() = engines.sumOf { it.requests }
    val totalTokens: Int get() = engines.sumOf { it.inputTokens + it.outputTokens }
    fun of(engine: AiEngine): EngineUsage? = engines.firstOrNull { it.engine == engine.id }
}

@Serializable
data class EngineUsage(
    val engine: String,
    val requests: Int,
    val errors: Int,
    val cached: Int,
    @kotlinx.serialization.SerialName("input_tokens") val inputTokens: Int,
    @kotlinx.serialization.SerialName("output_tokens") val outputTokens: Int,
    @kotlinx.serialization.SerialName("search_queries") val searchQueries: Int,
    @kotlinx.serialization.SerialName("daily_limit") val dailyLimit: Int,
)

sealed class BackendException(message: String) : Exception(message) {
    class MissingToken : BackendException("앱 인증 토큰이 설정되지 않았습니다. 설정에서 토큰을 입력하세요.")
    class Unauthorized : BackendException("앱 인증 토큰이 올바르지 않습니다.")
    class ServerNotConfigured(val detail: String?) :
        BackendException("백엔드 서버 설정이 완료되지 않았습니다." + (detail?.let { " ($it)" } ?: ""))
    class Http(val status: Int, val detail: String?) :
        BackendException("백엔드 오류 $status" + (detail?.let { ": $it" } ?: ""))
    class Network(val reason: String) : BackendException("백엔드에 연결할 수 없습니다: $reason")
    class Decoding(val reason: String) : BackendException("백엔드 응답을 해석할 수 없습니다: $reason")
}

/**
 * JARVIS 백엔드 HTTP 클라이언트.
 *
 * 상태 확인(`/health`, `/v1/engines`)과 사용량(`/v1/usage`). AI 요청 스트림은 [BackendAssistService].
 */
class BackendClient(
    val configuration: BackendConfiguration,
    private val transport: HttpTransport,
    private val tokenProvider: AuthTokenProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 백엔드 생존 확인 (인증 불필요). */
    suspend fun health(): HealthResponse = get("/health", authenticated = false, HealthResponse.serializer())

    /** 백엔드에 설정된 엔진 목록 (인증 필요). */
    suspend fun engines(): EnginesResponse = get("/v1/engines", authenticated = true, EnginesResponse.serializer())

    /** 오늘의 API 사용량 (인증 필요). */
    suspend fun usage(): UsageResponse = get("/v1/usage", authenticated = true, UsageResponse.serializer())

    internal fun buildRequest(path: String, authenticated: Boolean): HttpRequest {
        val headers = buildMap {
            put("Accept", "application/json")
            if (authenticated) {
                val token = tokenProvider.currentToken()?.takeIf { it.isNotBlank() }
                    ?: throw BackendException.MissingToken()
                put("Authorization", "Bearer $token")
            }
        }
        return HttpRequest(configuration.url(path), headers = headers, timeoutMillis = configuration.timeoutMillis)
    }

    private suspend fun <T> get(path: String, authenticated: Boolean, serializer: KSerializer<T>): T {
        val request = buildRequest(path, authenticated)
        val response = try {
            transport.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendException.Network(e.message ?: e::class.simpleName.orEmpty())
        }

        return when (response.status) {
            in 200..299 -> try {
                json.decodeFromString(serializer, response.body)
            } catch (e: SerializationException) {
                throw BackendException.Decoding(e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                throw BackendException.Decoding(e.message.orEmpty())
            }
            401, 403 -> throw BackendException.Unauthorized()
            503 -> throw BackendException.ServerNotConfigured(detail(response.body))
            else -> throw BackendException.Http(response.status, detail(response.body))
        }
    }

    private fun detail(body: String): String? =
        runCatching { json.decodeFromString(ErrorBody.serializer(), body).detail }.getOrNull()
}

object EngineStatusMapper {
    /** 백엔드 응답을 엔진별 상태로 변환한다. 응답에 없는 원격 엔진은 미설정. */
    fun statuses(response: EnginesResponse): Map<AiEngine, ConnectionStatus> {
        val result = AiEngine.remoteEngines.associateWith<AiEngine, ConnectionStatus> { ConnectionStatus.NotConfigured }
            .toMutableMap()
        for (info in response.engines) {
            val engine = info.engine ?: continue
            if (!engine.isRemote) continue
            result[engine] = if (info.configured) ConnectionStatus.Configured else ConnectionStatus.NotConfigured
        }
        return result
    }
}
