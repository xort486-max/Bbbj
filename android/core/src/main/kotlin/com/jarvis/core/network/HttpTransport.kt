package com.jarvis.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 30_000,
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
                Request.Builder()
                    .url(request.url)
                    .method(request.method, null)
                    .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                    .build(),
            )
        call.execute().use { response -> HttpResponse(response.code, response.body.string()) }
    }
}

/** 사용자 인증 토큰 제공자. 앱에서는 Keystore 기반 저장소가 구현한다. */
fun interface AuthTokenProvider {
    fun currentToken(): String?
}
