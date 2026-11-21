package com.jarvis.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 30_000,
    /** JSON 본문 (POST). */
    val body: String? = null,
)

data class HttpResponse(val status: Int, val body: String)

/** HTTP 전송 추상화. 테스트에서는 가짜 전송 계층을 주입한다. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** OkHttp 기반 기본 전송 계층. */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val call = client.newBuilder()
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
            .newCall(
                request.toOkHttp(),
            )
        call.execute().use { response -> HttpResponse(response.code, response.body.string()) }
    }
}

internal fun HttpRequest.toOkHttp(): Request = Request.Builder()
    .url(url)
    .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
    .apply { headers.forEach { (name, value) -> header(name, value) } }
    .build()

/** 서버 전송 이벤트(SSE) 스트림 전송 계층. 연결 오류·HTTP 오류는 [BackendException]으로 던진다. */
fun interface SseTransport {
    fun open(request: HttpRequest): Flow<SseEvent>
}

/**
 * OkHttp 기반 SSE 전송. 대형 모델 추론처럼 이벤트 사이 간격이 긴 응답을 위해 읽기 제한을 넉넉히 둔다.
 * 수집(collect)을 취소하면 HTTP 연결도 즉시 끊는다 → 백엔드도 처리를 중단한다.
 */
class OkHttpSseTransport(
    client: OkHttpClient = OkHttpClient(),
    readTimeoutSeconds: Long = 180,
) : SseTransport {
    private val client = client.newBuilder()
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun open(request: HttpRequest): Flow<SseEvent> = flow {
        val call = client.newCall(request.toOkHttp())
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = try {
            call.execute()
        } catch (e: java.io.IOException) {
            throw BackendException.Network(e.message ?: e::class.simpleName.orEmpty())
        }
        response.use {
            if (!it.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(it.body.string()).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw when (it.code) {
                    401, 403 -> BackendException.Unauthorized()
                    409 -> BackendException.Http(409, detail ?: "같은 요청을 이미 처리하고 있습니다.")
                    else -> BackendException.Http(it.code, detail)
                }
            }
            val source = it.body.source()
            var name = "message"
            val data = StringBuilder()
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) emit(SseEvent(name, data.toString()))
                            name = "message"
                            data.clear()
                        }
                        line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }
                if (data.isNotEmpty()) emit(SseEvent(name, data.toString()))
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                throw BackendException.Network(e.message ?: "스트림이 끊겼습니다")
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** 사용자 인증 토큰 제공자. 앱에서는 Keystore 기반 저장소가 구현한다. */
fun interface AuthTokenProvider {
    fun currentToken(): String?
}
